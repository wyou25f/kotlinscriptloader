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

/**
 * Lightweight KSL-aware completion for Kotlin script files.
 *
 * This intentionally offers documented public entry points and snippets. Kotlin's
 * own plugin remains responsible for semantic analysis, type inference and navigation.
 */
public final class KslCompletionContributor extends CompletionContributor {
    private record Suggestion(String lookup, String presentable, String type, String description) {}

    private static final List<Suggestion> SUGGESTIONS = List.of(
        new Suggestion("kslOrNull", "kslOrNull", "KSL API", "Nullable access to the installed KSL API"),
        new Suggestion("ksl", "ksl", "KSL API", "Required access to the installed KSL API"),
        new Suggestion("kslApi", "kslApi", "KSL API", "Alias for the installed KSL API"),
        new Suggestion("isKslAvailable", "isKslAvailable", "KSL API", "Whether KSL has completed initialization"),
        new Suggestion("KSLApiProvider.requireApi()", "KSLApiProvider.requireApi()", "KSL API", "Obtain the installed public API"),
        new Suggestion("runtimeStats()", "runtimeStats()", "Diagnostics", "Read the current KSL runtime snapshot"),
        new Suggestion("runtime()", "runtime()", "Runtime", "Alias for the read-only JVM runtime snapshot"),
        new Suggestion("availableProcessors()", "availableProcessors()", "Runtime", "Get the processors visible to the JVM"),
        new Suggestion("heapUsedBytes()", "heapUsedBytes()", "Runtime", "Read current JVM heap usage in bytes"),
        new Suggestion("heapMaxBytes()", "heapMaxBytes()", "Runtime", "Read the configured JVM heap maximum"),
        new Suggestion("KSLTopics.SCRIPT_LOADED", "KSLTopics.SCRIPT_LOADED", "Event bus", "Topic published when a script loads"),
        new Suggestion("KSLTopics.SCRIPT_FAILED", "KSLTopics.SCRIPT_FAILED", "Event bus", "Topic published when a script fails"),
        new Suggestion("KSLTopics.SCRIPT_UNLOADED", "KSLTopics.SCRIPT_UNLOADED", "Event bus", "Topic published when a script unloads"),
        new Suggestion("registerService", "registerService", "KSL API", "Register a service for other addons"),
        new Suggestion("service<T>(\"\")", "service<T>(\"\")", "KSL API", "Look up an optional typed service"),
        new Suggestion("requireService<T>(\"\")", "requireService<T>(\"\")", "KSL API", "Look up a required typed service"),
        new Suggestion("registerContextExtension", "registerContextExtension", "KSL API", "Extend the receiver available to .kts scripts"),
        new Suggestion("addDefaultImports", "addDefaultImports", "KSL API", "Add imports to KSL script compilation"),
        new Suggestion("subscribe", "subscribe", "Event bus", "Subscribe to a KSL event-bus topic"),
        new Suggestion("publish", "publish", "Event bus", "Publish a KSL event-bus message"),
        new Suggestion("reloadScript", "reloadScript", "KSL API", "Reload one script by id"),
        new Suggestion("reloadScriptsAsync", "reloadScriptsAsync", "KSL API", "Compile scripts asynchronously and apply on the server thread"),
        new Suggestion("onlinePlayers()", "onlinePlayers()", "Bukkit convenience", "Get a snapshot of online players (server thread)"),
        new Suggestion("findOnlinePlayer", "findOnlinePlayer", "Bukkit convenience", "Find an online player by name or UUID"),
        new Suggestion("broadcastRich", "broadcastRich", "Bukkit convenience", "Broadcast MiniMessage-formatted text"),
        new Suggestion("addonScope()", "addonScope()", "Addon lifecycle", "Create a resource scope owned by an addon"),
        new Suggestion("KSLTaskSpec", "KSLTaskSpec", "kslshu", "Describe work for a registered KSL scheduler"),
        new Suggestion("KSLTaskSchedulerFactory", "KSLTaskSchedulerFactory", "kslshu", "Implement a custom scheduler engine"),
        new Suggestion("KSLTaskSchedulerRegistry", "KSLTaskSchedulerRegistry", "kslshu", "Register and create scheduler implementations"),
        new Suggestion("kslTestSuite", "kslTestSuite", "Testing DSL", "Declare a KSL script test suite"),
        new Suggestion("testEach", "testEach", "Testing DSL", "Run a test for each supplied fixture"),
        new Suggestion("assertApproxEquals", "assertApproxEquals", "Testing DSL", "Assert approximate numeric equality"),
        new Suggestion("scriptCommand", "scriptCommand", "Command DSL", "Declare a script command"),
        new Suggestion("scriptTasks", "scriptTasks", "Task DSL", "Declare scheduled script tasks"),
        new Suggestion("scriptEvents", "scriptEvents", "Event DSL", "Group Bukkit event handlers in a DSL block")
    );

    public KslCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(),
            new CompletionProvider<>() {
                @Override
                protected void addCompletions(
                    @NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext context,
                    @NotNull CompletionResultSet result
                ) {
                    PsiFile file = parameters.getOriginalFile();
                    if (!file.getName().endsWith(".kts")) {
                        return;
                    }

                    for (Suggestion suggestion : SUGGESTIONS) {
                        result.addElement(LookupElementBuilder.create(suggestion.lookup())
                            .withPresentableText(suggestion.presentable())
                            .withTypeText(suggestion.type())
                            .withTailText("  " + suggestion.description(), true)
                            .withCaseSensitivity(false));
                    }
                }
            });
    }
}
