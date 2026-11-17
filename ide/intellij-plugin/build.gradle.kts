plugins {
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "ru.privateserver.ksl"
version = "1.2.2"

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
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        name = "Kotlin Script Loader"
        version = project.version.toString()
        description = """
            <p>Editor assistance for Kotlin Script Loader server scripts and addons.</p>
            <p>Provides KSL API completion, dependency and API diagnostics, quick fixes, and local static preflight checks for KSL .kts scripts and addon Kotlin sources.</p>
        """.trimIndent()
        vendor {
            name = "KotlinScriptLoader"
            url = "https://github.com/wyou25f/kotlinscriptloader"
        }
        ideaVersion {
            sinceBuild = "243"
        }
        changeNotes = """
            1.2.2: Add structured KSL logger completion, println-to-scriptLogger quick fix, logging diagnostics, and expanded Kotlin addon authoring support.
        """.trimIndent()
    }
}
