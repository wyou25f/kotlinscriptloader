package ru.privateserver.ksl.intellij.completion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KslCompletionContributorTest {
    @Test
    void suggestsNewDatabaseNbtSchematicAndCsvApis() {
        var suggestions = KslCompletionContributor.suggestions();
        assertTrue(suggestions.stream().anyMatch(item -> item.lookup().equals("loadNBT(fileName)")));
        assertTrue(suggestions.stream().anyMatch(item -> item.lookup().equals("loadSchematic(fileName)")));
        assertTrue(suggestions.stream().anyMatch(item -> item.lookup().equals("csvTable(\"id\", \"name\")")));
        assertTrue(suggestions.stream().anyMatch(item -> item.lookup().equals("editCsv(fileName, columns) { }")));
        assertTrue(suggestions.stream().anyMatch(item -> item.lookup().equals("csvSchema { ... }")));
        assertTrue(suggestions.stream().anyMatch(item -> item.lookup().equals("table.upsertBy(\"id\", values)")));
    }

    @Test
    void enablesCompletionForKslScripts() {
        assertTrue(KslCompletionContributor.supportsFile("join.kts", ""));
    }

    @Test
    void enablesCompletionForKslAddonKotlinSources() {
        assertTrue(KslCompletionContributor.supportsFile("ExampleAddon.kt",
            "class ExampleAddon : JavaPlugin(), KSLAddon"));
        assertTrue(KslCompletionContributor.supportsFile("Services.kt",
            "import ru.privateserver.ksl.api.*"));
    }

    @Test
    void offersStructuredLoggerAndAddonLoggerSuggestions() {
        assertTrue(KslCompletionContributor.hasSuggestion("scriptLogger()"));
        assertTrue(KslCompletionContributor.hasSuggestion("KSLLogLevel.DEBUG"));
        assertTrue(KslCompletionContributor.hasSuggestion("logger.child(\"database\")"));
        assertTrue(KslCompletionContributor.hasSuggestion("api.createLogger(addonId, \"database\")"));
    }

    @Test
    void suggestsSaferDatabaseAndDetachedNbtApis() {
        assertTrue(KslCompletionContributor.hasSuggestion("dbExecuteAsync(sql, ...)"));
        assertTrue(KslCompletionContributor.hasSuggestion("dbQueryAsync(sql, ...)"));
        assertTrue(KslCompletionContributor.hasSuggestion("dbQueryListAsync(sql, ...)"));
        assertTrue(KslCompletionContributor.hasSuggestion("dbTransactionAsync { }"));
        assertTrue(KslCompletionContributor.hasSuggestion("KSLNBTEngine.parse(snbt)"));
        assertTrue(KslCompletionContributor.hasSuggestion("KSLNBTLimits()"));
    }

    @Test
    void doesNotPolluteUnrelatedKotlinProjects() {
        assertFalse(KslCompletionContributor.supportsFile("Main.kt",
            "fun main() = println(\"hello\")"));
        assertFalse(KslCompletionContributor.supportsFile("README.md", "KSLAddon"));
    }
}
