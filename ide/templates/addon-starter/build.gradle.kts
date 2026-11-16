plugins {
    kotlin("jvm") version "2.0.21"
}

group = "example.addon"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val kslJar = providers.gradleProperty("kslJar")
    .orElse("libs/KotlinScriptLoader-1.3.0.jar")

dependencies {
    compileOnly(files(kslJar))
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

kotlin {
    jvmToolchain(21)
}
