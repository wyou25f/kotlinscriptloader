package ru.privateserver.ksl.intellij.inspection;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KslPreflightAnalyzerTest {
    @Test
    void reportsMissingPaperPlaceholderApiAndLuckPermsDependencies() {
        String source = """
            import org.bukkit.Bukkit
            import me.clip.placeholderapi.PlaceholderAPI
            import net.luckperms.api.LuckPerms
            """;
        List<KslPreflightAnalyzer.Diagnostic> diagnostics =
            KslPreflightAnalyzer.analyze(source, "plugins { kotlin(\"jvm\") }\ndependencies { }");
        assertEquals(3, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("missing-paper")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("missing-placeholderapi")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("missing-luckperms")));
    }

    @Test
    void acceptsDependenciesDeclaredInGradle() {
        String source = """
            import org.bukkit.Bukkit
            import me.clip.placeholderapi.PlaceholderAPI
            import net.luckperms.api.LuckPerms
            """;
        String gradle = """
            dependencies {
                compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
                compileOnly("me.clip:placeholderapi:2.11.6")
                compileOnly("net.luckperms:api:5.4")
            }
            """;
        assertTrue(KslPreflightAnalyzer.analyze(source, gradle).isEmpty());
    }

    @Test
    void detectsMisspelledKslApiMethodsOnlyInExplicitApiContext() {
        var diagnostics = KslPreflightAnalyzer.analyze(
            "KSL.api.relaodScripts()\nKSL.api.reloadScripts()", "");
        assertEquals(1, diagnostics.size());
        assertEquals("unknown-ksl-api", diagnostics.get(0).code());
        assertTrue(diagnostics.get(0).error());
    }

    @Test
    void warnsAboutDeprecatedAndVersionIncompatibleApi() {
        var diagnostics = KslPreflightAnalyzer.analyze(
            "PlayerChatEvent()\nMaterial.LEGACY_STONE",
            "dependencies { compileOnly(\"io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT\") }");
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("deprecated-api")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("version-compatibility")));
    }

    @Test
    void warnsAboutBlockingCallsAndSynchronousAddonReload() {
        var diagnostics = KslPreflightAnalyzer.analyze(
            "Thread.sleep(50)\nrunBlocking { refresh() }\napi.reloadScript(\"events/join\")", "");
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("blocking-call")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("blocking-reload")));
    }

    @Test
    void ignoresBlockingPatternsInsideCommentsAndStrings() {
        var diagnostics = KslPreflightAnalyzer.analyze(
            "// Thread.sleep(50)\nval text = \"runBlocking { }\"",
            "");
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void warnsAboutUnstructuredPrintCalls() {
        var diagnostics = KslPreflightAnalyzer.analyze(
            "println(\"ready\")\\nprint(value)", "");
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().allMatch(d -> d.code().equals("unstructured-log")));
        assertTrue(diagnostics.stream().noneMatch(KslPreflightAnalyzer.Diagnostic::error));
    }

    @Test
    void acceptsTheNewStructuredLoggerApiMethod() {
        var diagnostics = KslPreflightAnalyzer.analyze(
            "api.createLogger(\"example-addon\", \"storage\")", "");
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void recognizesAddonKotlinFilesForLocalPreflight() {
        assertTrue(KslPreflightAnalyzer.isKslAddonSource(
            "class ExampleAddon : JavaPlugin(), KSLAddon", "dependencies { compileOnly(files(\"KotlinScriptLoader.jar\")) }"));
    }

    @Test
    void ignoresApiLikeTextInsideStringsAndComments() {
        var diagnostics = KslPreflightAnalyzer.analyze(
            """
            // KSL.api.relaodScripts()
            val example = "KSL.api.relaodScripts()"
            """, "");
        assertTrue(diagnostics.isEmpty());
    }
}
