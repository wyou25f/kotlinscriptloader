plugins {
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "ru.privateserver.ksl"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.6")
        bundledPlugin("org.jetbrains.kotlin")
        pluginVerifier()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Kotlin Script Loader"
        version = project.version.toString()
        description = """
            <p>Editor assistance for Kotlin Script Loader server scripts.</p>
            <p>Provides KSL API and server scripting completions for Kotlin .kts files.</p>
        """.trimIndent()
        vendor {
            name = "KotlinScriptLoader"
            url = "https://github.com/wyou25f/kotlinscriptloader"
        }
        ideaVersion {
            sinceBuild = "243"
        }
        changeNotes = """
            Initial release: KSL API completion and server script templates.
        """.trimIndent()
    }
}
