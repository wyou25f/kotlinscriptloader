import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

val kslJarPath = providers.gradleProperty("kslJar")
    .orElse("libs/KotlinScriptLoader-1.2.1.jar")
val kslScriptsDir = providers.gradleProperty("kslScriptsDir")
    .orElse("../plugins/KotlinScriptLoader/scripts")

dependencies {
    // Contains the KSL @KotlinScript template, implicit receiver and public API.
    compileOnly(files(kslJarPath))
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.luckperms:api:5.5")
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        // Adds the live .kts directory to IDEA's project model for script indexing.
        kotlin.srcDir(kslScriptsDir)
    }
}

/**
 * Checks the exact template/API/stdlib classes used by the editor project.
 * Run after placing the matching plugin JAR at -PkslJar or ide/libs.
 */
tasks.register("verifyKslIdeSetup") {
    doLast {
        val jarFile = file(kslJarPath.get())
        check(jarFile.isFile) {
            "KSL plugin JAR not found: ${jarFile.absolutePath}. Set -PkslJar=/path/to/KotlinScriptLoader-1.2.0.jar"
        }
        ZipFile(jarFile).use { jar ->
            listOf(
                "ru/privateserver/ksl/KSLScript.class",
                "ru/privateserver/ksl/KSLScriptCompilationConfiguration.class",
                "ru/privateserver/ksl/api/KSLScriptMetadataKt.class",
                "ru/privateserver/ksl/api/KSLPublicApiKt.class",
                "ru/privateserver/ksl/api/KSLApiProvider.class",
                "ru/privateserver/ksl/std/text/TextExtensionsKt.class",
                "ru/privateserver/ksl/std/math/MathExtensionsKt.class",
                "ru/privateserver/ksl/std/time/TimeExtensionsKt.class",
                "ru/privateserver/ksl/std/player/PlayerExtensionsKt.class",
                "ru/privateserver/ksl/std/world/WorldExtensionsKt.class",
                "ru/privateserver/ksl/std/security/SecurityExtensionsKt.class",
                "ru/privateserver/ksl/std/toml/TomlExtensionsKt.class",
                "ru/privateserver/ksl/std/StandardAliasesKt.class"
            ).forEach { entry ->
                check(jar.getEntry(entry) != null) {
                    "KSL IDE support entry is missing from ${jarFile.name}: $entry"
                }
            }
        }
        logger.lifecycle("Verified KSL script template, public API and Standard Library in ${jarFile.name}")
    }
}
