plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

val kslJarPath = providers.gradleProperty("kslJar")
    .orElse("libs/KotlinScriptLoader-1.2.0.jar")
val kslScriptsDir = providers.gradleProperty("kslScriptsDir")
    .orElse("../plugins/KotlinScriptLoader/scripts")

dependencies {
    // The plugin JAR contains the @KotlinScript template and canonical compilation configuration.
    compileOnly(files(kslJarPath))
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.luckperms:api:5.5")
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        // Index live KSL scripts as part of this project for Kotlin analysis.
        kotlin.srcDir(kslScriptsDir)
    }
}

/**
 * Optional setup check. Run after placing the matching plugin JAR at -PkslJar
 * (or in ide/libs); intentionally not wired into build so a fresh clone syncs.
 */
tasks.register("verifyKslIdeSetup") {
    doLast {
        val jarFile = file(kslJarPath.get())
        check(jarFile.isFile) {
            "KSL plugin JAR not found: ${jarFile.absolutePath}. Set -PkslJar=/path/to/KotlinScriptLoader-1.2.0.jar"
        }
        java.util.zip.ZipFile(jarFile).use { jar ->
            listOf(
                "ru/privateserver/ksl/KSLScript.class",
                "ru/privateserver/ksl/KSLScriptCompilationConfiguration.class",
                "ru/privateserver/ksl/api/KSLScriptMetadataKt.class",
                "ru/privateserver/ksl/api/KSLPublicApiKt.class"
            ).forEach { entry ->
                check(jar.getEntry(entry) != null) {
                    "KSL IDE support entry is missing from ${jarFile.name}: $entry"
                }
            }
        }
        logger.lifecycle("KSL IDE template and public API are present in ${jarFile.name}")
    }
}
