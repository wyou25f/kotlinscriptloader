import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "9.4.2"
}

group = "ru.privateserver"
version = "1.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.essentialsx.net/releases/")
    maven("https://repo.codemc.org/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://maven.enginehub.org/repo/")
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
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.15") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinScriptingVersion")

    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:$kotlinScriptingVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // One driver for both MySQL and MariaDB servers.
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
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

val verifyShadowJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    doLast {
        val jarFile = tasks.shadowJar.get().archiveFile.get().asFile
        check(jarFile.isFile) { "Shadow JAR was not produced: ${jarFile.absolutePath}" }
        ZipFile(jarFile).use { jar ->
            check(jar.getEntry("plugin.yml") != null) { "plugin.yml is missing from the Shadow JAR" }
            check(jar.getEntry("ru/privateserver/ksl/KotlinScriptLoaderPlugin.class") != null) {
                "KotlinScriptLoaderPlugin main class is missing from the Shadow JAR"
            }
            val requiredClasses = listOf(
                "ru/privateserver/ksl/api/KSLApiProvider.class",
                "ru/privateserver/ksl/api/KSLPublicApiKt.class",
                "ru/privateserver/ksl/std/text/TextExtensionsKt.class",
                "ru/privateserver/ksl/std/math/MathExtensionsKt.class",
                "ru/privateserver/ksl/std/time/TimeExtensionsKt.class",
                "ru/privateserver/ksl/std/player/PlayerExtensionsKt.class",
                "ru/privateserver/ksl/std/inventory/InventoryExtensionsKt.class",
                "ru/privateserver/ksl/std/item/ItemExtensionsKt.class",
                "ru/privateserver/ksl/std/component/ComponentExtensionsKt.class",
                "ru/privateserver/ksl/std/world/WorldExtensionsKt.class"
            )
            val missing = requiredClasses.filter { jar.getEntry(it) == null }
            check(missing.isEmpty()) { "Required KSL API/stdlib classes are missing: ${missing.joinToString()}" }
        }
        logger.lifecycle("Verified plugin JAR: ${jarFile.name} (${jarFile.length()} bytes)")
    }
}

tasks.build {
    dependsOn(verifyShadowJar)
}


tasks.test {
    useJUnitPlatform()
}
