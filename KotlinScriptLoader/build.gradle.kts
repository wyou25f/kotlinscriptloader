plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "9.4.2"
}

group = "ru.privateserver"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.essentialsx.net/releases/")
    maven("https://repo.codemc.org/repository/maven-public/")
    maven("https://jitpack.io")
}

val kotlinScriptingVersion = "2.0.21"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("net.essentialsx:EssentialsX:2.21.2")
    compileOnly("net.skinsrestorer:skinsrestorer-api:15.5.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinScriptingVersion")

    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:$kotlinScriptingVersion")

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("com.zaxxer.hikari", "ru.privateserver.ksl.libs.hikari")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
