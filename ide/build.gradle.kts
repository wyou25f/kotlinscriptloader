plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    // Download/copy the matching KSL plugin JAR into ide/libs/.
    compileOnly(files("libs/KotlinScriptLoader-1.2.0.jar"))
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.luckperms:api:5.5")
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        // The live script folder is configurable; .kts files are indexed by IDEA.
        kotlin.srcDir(providers.gradleProperty("kslScriptsDir")
            .orElse("../plugins/KotlinScriptLoader/scripts"))
    }
}
