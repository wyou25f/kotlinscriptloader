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

    static boolean supportsFile(String name, String source) {
        if (name == null) return false;
        if (name.endsWith(".kts")) return true;
        if (!name.endsWith(".kt") || source == null) return false;
        return source.contains("KSLAddon")
            || source.contains("ru.privateserver.ksl")
            || source.matches("(?s).*\\bKSL\\s*\\..*");
    }
}
