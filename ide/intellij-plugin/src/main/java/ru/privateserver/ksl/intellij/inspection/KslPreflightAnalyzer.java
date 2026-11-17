package ru.privateserver.ksl.intellij.inspection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, non-executing checks for KSL Kotlin scripts and Gradle classpaths. */
public final class KslPreflightAnalyzer {
    static final int MAX_SOURCE_CHARS = 1_000_000;
    private static final Pattern SQL_CALL = Pattern.compile(
        "\\bdb(?:Execute|Query|Transaction)[A-Za-z0-9_]*\\s*\\("
    );
    private static final Pattern SQL_PREFIX = Pattern.compile(
        "(?is)^\\s*(?:SELECT|INSERT|UPDATE|DELETE|REPLACE|WITH|CREATE|ALTER|DROP|TRUNCATE)\\b"
    );
    private static final Pattern ASYNC_FUTURE_CALL = Pattern.compile(
        "\\b(?:db(?:Execute|Query|Transaction)[A-Za-z0-9_]*|(?:execute|query|queryList|queryOne|transaction)|reload(?:Script|Scripts|All))Async\\s*\\("
    );
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.*]+)");
    private static final Pattern API_CALL = Pattern.compile("\\b(?:KSL\\s*\\.\\s*api|api)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern PAPER_VERSION = Pattern.compile("(?:paper-api|spigot-api)[^\\n\\r]*?(1\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> API_METHODS = Set.of(
        "createConfig", "createLogger", "logger", "registerAddon", "unregisterAddon", "registerService", "unregisterService",
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
    private static final Pattern UNSTRUCTURED_PRINT = Pattern.compile("\\b(println|print)\\s*\\(");

    private KslPreflightAnalyzer() {}

    public record Diagnostic(String code, int startOffset, int endOffset, String message, boolean error) {}

    public static List<Diagnostic> analyze(String source, String buildConfiguration) {
        if (source == null || source.isEmpty()) return List.of();
        if (source.length() > MAX_SOURCE_CHARS) {
            return List.of(new Diagnostic("analysis-limit", 0, Math.min(1, source.length()),
                "KSL static analysis was skipped because this file exceeds 1,000,000 characters. Split the file or run the normal Gradle/KSL validation; no safety conclusion was made.", false));
        }
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
        addDynamicSqlDiagnostics(result, code, source);
        addBlockingFutureWaitDiagnostics(result, code);
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
        addMatches(result, UNSTRUCTURED_PRINT, code, "unstructured-log",
            "Bare print/println output has no KSL owner, category or server-controlled level. Prefer scriptLogger().info(...).", false);
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


    private record StringLiteral(int endOffset, int contentStart, int contentEnd, String value) {}

    /**
     * Flags SQL assembled through Kotlin interpolation or string concatenation.
     * Only recognizable SQL statements passed to known KSL/session methods are considered.
     */
    private static void addDynamicSqlDiagnostics(List<Diagnostic> target, String code, String source) {
        Matcher calls = SQL_CALL.matcher(code);
        while (calls.find()) {
            int literalStart = skipWhitespace(source, calls.end());
            StringLiteral literal = readStringLiteral(source, literalStart);
            if (literal == null || !SQL_PREFIX.matcher(literal.value()).find()) continue;

            int afterLiteral = skipWhitespace(source, literal.endOffset());
            boolean concatenated = afterLiteral < source.length() && source.charAt(afterLiteral) == '+';
            if (!concatenated && !containsKotlinInterpolation(source, literal.contentStart(), literal.contentEnd())) continue;

            target.add(new Diagnostic("dynamic-sql", literalStart, literal.endOffset(),
                "This SQL statement is assembled dynamically. Prefer SQL placeholders (?) and pass values as parameters to the KSL database method; this keeps data separate from SQL syntax.", false));
        }
    }

    private static void addBlockingFutureWaitDiagnostics(List<Diagnostic> target, String code) {
        Matcher calls = ASYNC_FUTURE_CALL.matcher(code);
        while (calls.find()) {
            int open = code.indexOf('(', calls.start());
            int close = matchingParenthesis(code, open);
            if (close < 0) continue;

            int cursor = skipWhitespace(code, close + 1);
            if (cursor >= code.length() || code.charAt(cursor) != '.') continue;
            cursor = skipWhitespace(code, cursor + 1);
            int methodStart = cursor;
            while (cursor < code.length() && Character.isJavaIdentifierPart(code.charAt(cursor))) cursor++;
            String method = code.substring(methodStart, cursor);
            if (!method.equals("get") && !method.equals("join")) continue;
            cursor = skipWhitespace(code, cursor);
            if (cursor >= code.length() || code.charAt(cursor) != '(') continue;

            target.add(new Diagnostic("blocking-future-wait", methodStart, cursor,
                "Waiting with ." + method + "() blocks the calling thread. In server callbacks prefer whenComplete/thenApply or an async workflow; only block a dedicated worker when a bounded wait is intentional.", false));
        }
    }

    private static int matchingParenthesis(String source, int open) {
        if (open < 0 || open >= source.length() || source.charAt(open) != '(') return -1;
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '(') depth++;
            else if (current == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int skipWhitespace(String source, int offset) {
        int cursor = offset;
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
        return cursor;
    }

    private static StringLiteral readStringLiteral(String source, int start) {
        if (start < 0 || start >= source.length() || source.charAt(start) != '"') return null;
        boolean triple = start + 2 < source.length() && source.startsWith("\"\"\"", start);
        int contentStart = start + (triple ? 3 : 1);
        int cursor = contentStart;
        while (cursor < source.length()) {
            if (triple && cursor + 2 < source.length() && source.startsWith("\"\"\"", cursor)) {
                return new StringLiteral(cursor + 3, contentStart, cursor, source.substring(contentStart, cursor));
            }
            char current = source.charAt(cursor);
            if (!triple && current == '\\') {
                cursor = Math.min(source.length(), cursor + 2);
                continue;
            }
            if (!triple && current == '"') {
                return new StringLiteral(cursor + 1, contentStart, cursor, source.substring(contentStart, cursor));
            }
            cursor++;
        }
        return null;
    }

    private static boolean containsKotlinInterpolation(String source, int start, int end) {
        for (int i = start; i < end; i++) {
            if (source.charAt(i) != '$') continue;
            int slashes = 0;
            for (int j = i - 1; j >= start && source.charAt(j) == '\\'; j--) slashes++;
            if (slashes % 2 != 0) continue;
            if (i + 1 < end && (source.charAt(i + 1) == '{' || Character.isJavaIdentifierStart(source.charAt(i + 1)))) {
                return true;
            }
        }
        return false;
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
        int blockDepth = 0;
        boolean lineComment = false;
        boolean string = false;
        boolean tripleString = false;
        boolean character = false;
        boolean escaped = false;

        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            char third = i + 2 < source.length() ? source.charAt(i + 2) : '\0';

            if (lineComment) {
                if (c == '\n' || c == '\r') lineComment = false;
                else blank(out, i, i + 1);
                i++;
                continue;
            }
            if (blockDepth > 0) {
                blank(out, i, i + 1);
                if (c == '/' && next == '*') {
                    blank(out, i + 1, i + 2);
                    blockDepth++;
                    i += 2;
                } else if (c == '*' && next == '/') {
                    blank(out, i + 1, i + 2);
                    blockDepth--;
                    i += 2;
                } else i++;
                continue;
            }
            if (tripleString) {
                blank(out, i, i + 1);
                if (c == '"' && next == '"' && third == '"') {
                    blank(out, i + 1, i + 3);
                    i += 3;
                    tripleString = false;
                } else i++;
                continue;
            }
            if (string || character) {
                blank(out, i, i + 1);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if ((string && c == '"') || (character && c == '\'')) {
                    string = false;
                    character = false;
                }
                i++;
                continue;
            }

            if (c == '/' && next == '/') {
                blank(out, i, i + 2);
                i += 2;
                lineComment = true;
            } else if (c == '/' && next == '*') {
                blank(out, i, i + 2);
                i += 2;
                blockDepth = 1;
            } else if (c == '"' && next == '"' && third == '"') {
                blank(out, i, i + 3);
                i += 3;
                tripleString = true;
            } else if (c == '"') {
                blank(out, i, i + 1);
                i++;
                string = true;
            } else if (c == '\'') {
                blank(out, i, i + 1);
                i++;
                character = true;
            } else i++;
        }
        return out.toString();
    }

    private static void blank(StringBuilder out, int start, int end) {
        for (int i = start; i < end; i++) if (out.charAt(i) != '\n' && out.charAt(i) != '\r') out.setCharAt(i, ' ');
    }
}
