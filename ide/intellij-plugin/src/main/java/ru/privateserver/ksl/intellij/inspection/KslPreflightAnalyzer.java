package ru.privateserver.ksl.intellij.inspection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, non-executing checks for KSL Kotlin scripts and Gradle classpaths. */
public final class KslPreflightAnalyzer {
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.*]+)");
    private static final Pattern API_CALL = Pattern.compile("\\b(?:KSL\\s*\\.\\s*api|api)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern PAPER_VERSION = Pattern.compile("(?:paper-api|spigot-api)[^\\n\\r]*?(1\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> API_METHODS = Set.of(
        "createConfig", "registerAddon", "unregisterAddon", "registerService", "unregisterService",
        "getService", "service", "requireService", "addDefaultImports", "registerStandardLibraryModule",
        "unregisterStandardLibraryModule", "standardLibraryModules", "registerContextExtension",
        "unregisterContextExtension", "registeredAddons", "isAddonLoaded", "onAddonReady",
        "subscribe", "publish", "reloadScripts", "registerHook", "unregisterHook", "getHook",
        "hooks", "isPluginEnabled", "scripts", "reloadScript", "reloadScriptAsync", "reloadScriptsAsync", "isSandboxed",
        "isConsoleCommandBlocked", "registerPlaceholder", "unregisterPlaceholder",
        "registerTextPlaceholder", "unregisterTextPlaceholder", "registerEffectShape",
        "registerEntityAttribute", "onlinePlayers", "findOnlinePlayer", "isPlayerOnline",
        "onlinePlayerCount", "broadcast", "broadcastRich", "isServerThread", "scheduleSync",
        "scheduleAsync", "scheduleSyncLater", "scheduleAsyncLater", "scheduleSyncRepeating",
        "scheduleAsyncRepeating", "addonScope", "runtimeStats", "runtime", "availableProcessors",
        "heapUsedBytes", "heapMaxBytes", "emit", "on", "addons", "addon", "hasAddon",
        "script", "reload", "reloadAll", "reloadAllAsync", "hasService", "pluginAvailable",
        "hookOrNull", "hookOrFail", "addonOrNull", "addonOrFail", "scriptOrNull", "scriptOrFail"
    );

    private static final Pattern THREAD_SLEEP = Pattern.compile("\\bThread\\s*\\.\\s*sleep\\s*\\(");
    private static final Pattern RUN_BLOCKING = Pattern.compile("\\brunBlocking\\s*\\{");
    private static final Pattern INFINITE_LOOP = Pattern.compile("\\bwhile\\s*\\(\\s*true\\s*\\)\\s*\\{");
    private static final Pattern RAW_THREAD = Pattern.compile("\\bThread\\s*(?:\\(|\\{)|\\bExecutors\\s*\\.\\s*new(?:SingleThreadExecutor|FixedThreadPool|CachedThreadPool|ScheduledThreadPool)");
    private static final Pattern SYNC_RELOAD = Pattern.compile("\\b(?:KSL\\s*\\.\\s*api|api)\\s*\\.\\s*reloadScript\\s*\\(");

    private KslPreflightAnalyzer() {}

    public record Diagnostic(String code, int startOffset, int endOffset, String message, boolean error) {}

    public static List<Diagnostic> analyze(String source, String buildConfiguration) {
        if (source == null || source.isEmpty()) return List.of();
        String config = buildConfiguration == null ? "" : buildConfiguration;
        boolean gradleModel = config.contains("dependencies") || config.contains("plugins");
        List<Diagnostic> result = new ArrayList<>();

        Matcher imports = IMPORT.matcher(source);
        while (imports.find()) {
            String name = imports.group(1);
            if (!gradleModel) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("org.bukkit.") || lower.startsWith("io.papermc.paper."))
                && !containsAny(config, "paper-api", "spigot-api")) {
                result.add(new Diagnostic("missing-paper", imports.start(1), imports.end(1),
                    "Paper/Spigot API import found, but no Paper API dependency is declared in the Gradle project.", false));
            } else if (lower.startsWith("me.clip.placeholderapi.")
                && !containsAny(config, "placeholderapi")) {
                result.add(new Diagnostic("missing-placeholderapi", imports.start(1), imports.end(1),
                    "PlaceholderAPI import found, but PlaceholderAPI is not declared as a project dependency.", false));
            } else if (lower.startsWith("net.luckperms.api.")
                && !containsAny(config, "luckperms-api", "luckperms")) {
                result.add(new Diagnostic("missing-luckperms", imports.start(1), imports.end(1),
                    "LuckPerms API import found, but LuckPerms API is not declared as a project dependency.", false));
            }
        }

        String code = maskCommentsAndStrings(source);
        addMatches(result, THREAD_SLEEP, code, "blocking-call",
            "Thread.sleep blocks the current thread and can stall script loading or synchronous callbacks. Use KSL scheduling/worker APIs.", false);
        addMatches(result, RUN_BLOCKING, code, "blocking-call",
            "runBlocking blocks the current thread. Move suspending work to a worker and hand Bukkit operations back to the server thread.", false);
        addMatches(result, INFINITE_LOOP, code, "unbounded-loop",
            "An unconditional while(true) loop can prevent the current callback from returning. Prefer a repeating KSL task.", false);
        addMatches(result, RAW_THREAD, code, "unmanaged-thread",
            "A manually created thread/executor is not owned by the script lifecycle. Prefer KSL workers or register explicit cleanup.", false);
        addMatches(result, SYNC_RELOAD, code, "blocking-reload",
            "KSLAPI.reloadScript blocks until compilation and swap finish. Use reloadScriptAsync from addon callbacks.", false);
        boolean explicitApiContext = code.contains("KSL.api")
            || Pattern.compile("\\b(?:val|var)\\s+api\\s*:\\s*(?:ru\\.privateserver\\.ksl\\.api\\.)?KSLAPI\\b")
                .matcher(code).find();
        if (explicitApiContext) {
            Matcher calls = API_CALL.matcher(code);
            while (calls.find()) {
                String method = calls.group(1);
                if (!API_METHODS.contains(method)) {
                    result.add(new Diagnostic("unknown-ksl-api", calls.start(1), calls.end(1),
                        "Unknown KSL API method '" + method + "'. Check spelling and the API version on the classpath.", true));
                }
            }
        }

        Matcher legacyChat = Pattern.compile("\\bPlayerChatEvent\\b").matcher(code);
        while (legacyChat.find()) {
            result.add(new Diagnostic("deprecated-api", legacyChat.start(), legacyChat.end(),
                "PlayerChatEvent is deprecated. Prefer AsyncChatEvent on modern Paper versions.", false));
        }

        Matcher target = PAPER_VERSION.matcher(config);
        int minor = target.find() ? parseMinor(target.group(1)) : -1;
        if (minor >= 13) {
            Matcher legacyMaterial = Pattern.compile("\\bMaterial\\s*\\.\\s*LEGACY_[A-Z0-9_]+").matcher(code);
            while (legacyMaterial.find()) {
                result.add(new Diagnostic("version-compatibility", legacyMaterial.start(), legacyMaterial.end(),
                    "Legacy Material constant is incompatible with the configured Minecraft 1." + minor + " target.", false));
            }
        }
        return List.copyOf(result);
    }

    public static boolean isKslAddonSource(String source, String buildConfiguration) {
        String code = source == null ? "" : source;
        String config = buildConfiguration == null ? "" : buildConfiguration.toLowerCase(Locale.ROOT);
        return code.contains("KSLAddon") || code.contains("KSL.api") || config.contains("kotlinscriptloader");
    }

    private static void addMatches(
        List<Diagnostic> target, Pattern pattern, String source, String code, String message, boolean error
    ) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            target.add(new Diagnostic(code, matcher.start(), matcher.end(), message, error));
        }
    }

    private static int parseMinor(String version) {
        try { return Integer.parseInt(version.substring(version.indexOf('.') + 1)); }
        catch (RuntimeException ignored) { return -1; }
    }

    private static boolean containsAny(String source, String... needles) {
        String lower = source.toLowerCase(Locale.ROOT);
        for (String needle : needles) if (lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String maskCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source);
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                if (end < 0) end = source.length();
                blank(out, i, end); i = end;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                blank(out, i, end); i = end;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                boolean triple = quote == '"' && i + 2 < source.length()
                    && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"';
                int start = i;
                i += triple ? 3 : 1;
                while (i < source.length()) {
                    if (triple && i + 2 < source.length() && source.startsWith("\"\"\"", i)) { i += 3; break; }
                    if (!triple && source.charAt(i) == quote && source.charAt(i - 1) != '\\') { i++; break; }
                    i++;
                }
                blank(out, start, i);
            } else i++;
        }
        return out.toString();
    }

    private static void blank(StringBuilder out, int start, int end) {
        for (int i = start; i < end; i++) if (out.charAt(i) != '\n' && out.charAt(i) != '\r') out.setCharAt(i, ' ');
    }
}
