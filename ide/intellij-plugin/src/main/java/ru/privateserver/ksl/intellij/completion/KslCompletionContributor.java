package ru.privateserver.ksl.intellij.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import com.intellij.patterns.PlatformPatterns;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** Lightweight KSL-aware completion for server scripts and addon source files. */
public final class KslCompletionContributor extends CompletionContributor {
    private record Suggestion(String lookup, String type, String description) {}

    private static final List<Suggestion> SUGGESTIONS = List.of(
        new Suggestion("kslOrNull", "KSL API", "Nullable access to the installed KSL API"),
        new Suggestion("ksl", "KSL API", "Required access to the installed KSL API"),
        new Suggestion("kslApi", "KSL API", "Alias for the installed KSL API"),
        new Suggestion("isKslAvailable", "KSL API", "Whether KSL has completed initialization"),
        new Suggestion("KSLApiProvider.requireApi()", "KSL API", "Obtain the installed public API"),
        new Suggestion("KSLAddonScope.create(api)", "Lifecycle", "Create a scope that owns listeners, tasks and subscriptions"),
        new Suggestion("api.createConfig(this)", "Configuration", "Load an addon-owned YAML config with defaults"),
        new Suggestion("scriptLogger()", "Logging", "Create an admin-aware logger for the current KSL script"),
        new Suggestion("scriptLogger(\"economy\") { level = KSLLogLevel.DEBUG }", "Logging DSL", "Create a named script logger with a requested level"),
        new Suggestion("kslLogger()", "Logging", "Short alias for scriptLogger"),
        new Suggestion("KSLLogger", "Logging API", "Structured logger shared by KSL scripts and addons"),
        new Suggestion("KSLLoggerConfig", "Logging API", "Configure level, prefix and owner/category fields"),
        new Suggestion("KSLLogLevel.TRACE", "Logging levels", "Most detailed logger level; the server admin can suppress it"),
        new Suggestion("KSLLogLevel.DEBUG", "Logging levels", "Debug detail; enabled only when the server policy allows it"),
        new Suggestion("logger.trace()", "Logging", "Write trace-level structured details"),
        new Suggestion("logger.debug()", "Logging", "Write debug-level structured details"),
        new Suggestion("logger.info()", "Logging", "Write an informational structured message"),
        new Suggestion("logger.warn()", "Logging", "Write a warning with optional fields"),
        new Suggestion("logger.error()", "Logging", "Write an error with optional cause and fields"),
        new Suggestion("logger.child(\"database\")", "Logging", "Create a child category under the current logger"),
        new Suggestion("api.createLogger(addonId, \"database\")", "Addon logging", "Create a logger owned by this addon"),
        new Suggestion("api.logger(addonId, \"database\") { }", "Addon logging DSL", "Create and configure an addon logger"),
        new Suggestion("api.registerService", "Services", "Register a service for other addons"),
        new Suggestion("api.service<T>(\"\")", "Services", "Look up an optional typed service"),
        new Suggestion("api.requireService<T>(\"\")", "Services", "Look up a required typed service"),
        new Suggestion("api.findAddon(\"\")", "Addons", "Find a loaded addon by id"),
        new Suggestion("api.requireAddon(\"\")", "Addons", "Find a required loaded addon"),
        new Suggestion("api.findScript(\"\")", "Scripts", "Find script metadata by id"),
        new Suggestion("api.requireScript(\"\")", "Scripts", "Find required script metadata"),
        new Suggestion("api.reloadScriptAsync(\"\")", "Scripts", "Reload one script without blocking the caller"),
        new Suggestion("api.reloadScriptsAsync", "Scripts", "Compile scripts asynchronously and apply on the server thread"),
        new Suggestion("api.onScriptLoaded", "Events", "Subscribe to typed script-loaded events"),
        new Suggestion("api.onScriptFailed", "Events", "Subscribe to typed script-failed events"),
        new Suggestion("api.onScriptUnloaded", "Events", "Subscribe to typed script-unloaded events"),
        new Suggestion("KSLTopics.SCRIPT_LOADED", "Events", "Built-in script lifecycle topic"),
        new Suggestion("api.registerContextExtension", "Script context", "Add typed helpers to the .kts receiver"),
        new Suggestion("api.addDefaultImports", "Script context", "Add imports to KSL script compilation"),
        new Suggestion("api.addImports", "Script context", "Kotlin-friendly alias for default imports"),
        new Suggestion("api.subscribe", "Event bus", "Subscribe to a KSL event-bus topic"),
        new Suggestion("api.publish", "Event bus", "Publish a KSL event-bus message"),
        new Suggestion("scope.listen", "Lifecycle", "Register a Bukkit listener owned by the addon scope"),
        new Suggestion("scope.repeat", "Lifecycle", "Schedule recurring work owned by the addon scope"),
        new Suggestion("scope.schedule", "Lifecycle", "Schedule work on the KSL worker engine"),
        new Suggestion("scope.runSync", "Threading", "Run Bukkit operations on the server thread"),
        new Suggestion("scope.runAsync", "Threading", "Run non-Bukkit work asynchronously"),
        new Suggestion("dbExecuteAsync(sql, ...)", "Database", "Run a parameterized database write on KSL's ordered writer queue"),
        new Suggestion("dbQueryAsync(sql, ...)", "Database", "Run a prepared query on KSL's reader pool and return detached data"),
        new Suggestion("dbQueryListAsync(sql, ...)", "Database", "Read a bounded list with the configured query row limit"),
        new Suggestion("dbTransactionAsync { }", "Database transactions", "Commit on success and roll back when the block throws"),
        new Suggestion("KSLNBTEngine.parse(snbt)", "NBT", "Parse a bounded detached SNBT document without mutating live Bukkit state"),
        new Suggestion("KSLNBTLimits()", "NBT safety", "Set SNBT size, nesting depth and entry-count limits"),
        new Suggestion("loadNBT(fileName)", "Binary NBT", "Load a bounded binary .nbt file as a detached document"),
        new Suggestion("editNBT(fileName) { }", "Binary NBT", "Edit and atomically save a binary .nbt document"),
        new Suggestion("copySchematicSelection(player)", "WorldEdit schematics", "Capture the current selection into a reusable clipboard"),
        new Suggestion("loadSchematic(fileName)", "WorldEdit schematics", "Load a bounded Sponge .schem clipboard"),
        new Suggestion("saveSchematic(fileName, clipboard)", "WorldEdit schematics", "Atomically save a Sponge .schem file"),
        new Suggestion("pasteSchematic(player, clipboard, location)", "WorldEdit schematics", "Paste a schematic through WorldEdit's undo-aware edit session"),
        new Suggestion("csvTable(\"id\", \"name\")", "CSV tables", "Create an in-memory CSV table using the standard DSL"),
        new Suggestion("loadCsv(fileName)", "CSV files", "Load a UTF-8 CSV table from the script folder"),
        new Suggestion("editCsv(fileName, columns) { }", "CSV files", "Edit and atomically save a CSV table"),
        new Suggestion("KslCsvOptions(...)", "CSV format", "Configure delimiter, quoting, headers and parser limits"),
        new Suggestion("table.filterRows { }", "CSV tables", "Create a new table using a row predicate"),
        new Suggestion("table.select(\"id\", \"name\")", "CSV tables", "Project a table onto selected columns")
        new Suggestion("onlinePlayers()", "Bukkit convenience", "Get a snapshot of online players"),
        new Suggestion("findOnlinePlayer", "Bukkit convenience", "Find an online player by name or UUID"),
        new Suggestion("broadcastRich", "Bukkit convenience", "Broadcast MiniMessage-formatted text"),
        new Suggestion("runtimeStats()", "Diagnostics", "Read the current KSL runtime snapshot"),
        new Suggestion("kslRuntimeStats", "Diagnostics", "Read-only JVM, CPU, thread and GC telemetry"),
        new Suggestion("availableProcessors()", "Diagnostics", "Get the processors visible to the JVM"),
        new Suggestion("heapUsedBytes()", "Diagnostics", "Read current JVM heap usage in bytes"),
        new Suggestion("heapMaxBytes()", "Diagnostics", "Read the configured JVM heap maximum"),
        new Suggestion("KSLTaskSpec", "Scheduler SPI", "Describe work for a registered KSL scheduler"),
        new Suggestion("KSLTaskSchedulerFactory", "Scheduler SPI", "Implement a custom scheduler engine"),
        new Suggestion("KSLTaskSchedulerRegistry", "Scheduler SPI", "Register and create scheduler implementations"),
        new Suggestion("kslTestSuite", "Testing DSL", "Declare a KSL script test suite"),
        new Suggestion("testEach", "Testing DSL", "Run a test for each supplied fixture"),
        new Suggestion("assertApproxEquals", "Testing DSL", "Assert approximate numeric equality"),
        new Suggestion("scriptCommand", "Script DSL", "Declare a script command"),
        new Suggestion("scriptTasks", "Script DSL", "Declare scheduled script tasks"),
        new Suggestion("scriptEvents", "Script DSL", "Group Bukkit event handlers in a DSL block")
    );

    public KslCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters,
                                          @NotNull ProcessingContext context,
                                          @NotNull CompletionResultSet result) {
                PsiFile file = parameters.getOriginalFile();
                if (!supportsFile(file.getName(), file.getText())) return;
                for (Suggestion suggestion : SUGGESTIONS) {
                    result.addElement(LookupElementBuilder.create(suggestion.lookup())
                        .withTypeText(suggestion.type())
                        .withTailText("  " + suggestion.description(), true)
                        .withCaseSensitivity(false));
                }
            }
        });
    }

    static boolean hasSuggestion(String lookup) {
        return SUGGESTIONS.stream().anyMatch(suggestion -> suggestion.lookup().equals(lookup));
    }

    static boolean supportsFile(String name, String source) {
        if (name == null) return false;
        if (name.endsWith(".kts")) return true;
        if (!name.endsWith(".kt") || source == null) return false;
        return source.contains("KSLAddon")
            || source.contains("ru.privateserver.ksl")
            || source.matches("(?s).*\\bKSL\\s*\\..*");
    }
}
