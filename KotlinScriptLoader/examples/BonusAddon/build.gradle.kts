plugins {
    kotlin("jvm") version "2.0.21"
}

group = "ru.example.bonus"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly(files("../../build/libs/KotlinScriptLoader-1.1.0.jar"))
    compileOnly(files("../ExampleAddon/build/libs/ExampleAddon-1.0.0.jar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
kotlin { jvmToolchain(21) }

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
