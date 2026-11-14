import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "9.4.2"
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

group = "ru.privateserver"
version = "1.2.1"

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

// Paper downloads these dependencies into its library cache and exposes them to the plugin
// classloader. Use -PfatJar for an offline/self-contained build.
val fatJar = providers.gradleProperty("fatJar").map(String::toBoolean).getOrElse(false)
val kotlinLibraries = listOf(
    "org.jetbrains.kotlin:kotlin-stdlib:$kotlinScriptingVersion",
    "org.jetbrains.kotlin:kotlin-reflect:$kotlinScriptingVersion",
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinScriptingVersion",
    "org.jetbrains.kotlin:kotlin-scripting-common:$kotlinScriptingVersion",
    "org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinScriptingVersion",
    "org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinScriptingVersion",
    "org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinScriptingVersion",
    "org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:$kotlinScriptingVersion",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1",
    "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3"
)

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

    // Compile against Kotlin in both modes; only the fat build embeds it.
    kotlinLibraries.forEach { coordinates ->
        if (fatJar) implementation(coordinates) else compileOnly(coordinates)
    }

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.github.seeseemelk:MockBukkit-v1.21:3.133.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    // The production artifact relies on Paper libraries; tests need Kotlin and scripting APIs
    // on both the test compilation and execution classpaths.
    kotlinLibraries.forEach { testImplementation(it) }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

val librariesYaml = kotlinLibraries.joinToString(", ", "[", "]") { "\"$it\"" }
    .let { if (fatJar) "[]" else it }

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version, "libraries" to librariesYaml)
    }
}

tasks.shadowJar {
    archiveClassifier.set(if (fatJar) "fat" else "")
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
                "ru/privateserver/ksl/KSLAddonConfig.class",
                "ru/privateserver/ksl/api/KSLApiProvider.class",
                "ru/privateserver/ksl/api/KSLPublicApiKt.class",
                "ru/privateserver/ksl/std/text/TextExtensionsKt.class",
                "ru/privateserver/ksl/std/math/MathExtensionsKt.class",
                "ru/privateserver/ksl/std/time/TimeExtensionsKt.class",
                "ru/privateserver/ksl/std/player/PlayerExtensionsKt.class",
                "ru/privateserver/ksl/std/inventory/InventoryExtensionsKt.class",
                "ru/privateserver/ksl/std/item/ItemExtensionsKt.class",
                "ru/privateserver/ksl/std/component/ComponentExtensionsKt.class",
                "ru/privateserver/ksl/std/world/WorldExtensionsKt.class",
                "ru/privateserver/ksl/std/json/JsonExtensionsKt.class",
                "ru/privateserver/ksl/std/security/SecurityExtensionsKt.class",
                "ru/privateserver/ksl/std/security/KSLSecurity.class",
                "ru/privateserver/ksl/std/toml/KSLTomlCodec.class",
                "ru/privateserver/ksl/std/toml/TomlExtensionsKt.class",
                "ru/privateserver/ksl/std/StandardAliasesKt.class",
                "ru/privateserver/ksl/meslib2/MesLib2.class",
                "ru/privateserver/ksl/meslib2/MesLib2Info.class",
                "ru/privateserver/ksl/rt/KSLCache.class",
                "ru/privateserver/ksl/rt/KSLProfiler.class",
                "ru/privateserver/ksl/rt/KSLRuntimeScope.class",
                "ru/privateserver/ksl/rt/kslrt.class"
            )
            val missing = requiredClasses.filter { jar.getEntry(it) == null }
            check(missing.isEmpty()) { "Required KSL API/stdlib classes are missing: ${missing.joinToString()}" }

            val kotlinClasses = jar.entries().asSequence().filter {
                it.name.startsWith("kotlin/") || it.name.startsWith("kotlinx/")
            }.map { it.name }.toList()
            if (fatJar) {
                check(jar.getEntry("kotlin/jvm/internal/Intrinsics.class") != null) {
                    "Fat JAR is missing Kotlin runtime classes"
                }
            } else {
                check(kotlinClasses.isEmpty()) {
                    "Thin JAR unexpectedly embeds Kotlin classes: ${kotlinClasses.take(10).joinToString()}"
                }
            }
            val descriptor = jar.getInputStream(jar.getEntry("plugin.yml")).bufferedReader().use { it.readText() }
            check(descriptor.contains(if (fatJar) "libraries: []" else "libraries: [")) {
                "plugin.yml libraries declaration does not match the selected JAR mode"
            }
        }
        logger.lifecycle("Verified ${if (fatJar) "fat" else "thin"} plugin JAR: ${jarFile.name} (${jarFile.length()} bytes)")
    }
}

tasks.build {
    dependsOn(verifyShadowJar)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register("diagnoseBuild") {
    group = "help"
    description = "Prints the Java, Gradle, Kotlin and operating-system details used by this build."
    doLast {
        logger.lifecycle("KSL build environment")
        logger.lifecycle("  Gradle: ${gradle.gradleVersion}")
        logger.lifecycle("  Java: ${System.getProperty("java.version")}")
        logger.lifecycle("  Java home: ${System.getProperty("java.home")}")
        logger.lifecycle("  Kotlin plugin: $kotlinScriptingVersion")
        logger.lifecycle("  OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        logger.lifecycle("  Project: ${project.path} (${project.version})")
        logger.lifecycle("  Fat JAR: $fatJar")
    }
}


// A deliberately conservative baseline: the threshold is enforced, not merely reported.
// Raise this value as the test suite grows; do not count generated or external code as coverage.
kover {
    reports {
        verify {
            rule("Minimum project line coverage") {
                minBound(10)
            }
        }
    }
}

// Kover reports expose Kotlin line coverage after the same test run used by the quality gate.
tasks.matching { it.name == "koverHtmlReport" || it.name == "koverXmlReport" }.configureEach {
    dependsOn(tasks.test)
}

/**
 * Package-level audit row. Kept at script scope to avoid local-class codegen issues
 * in Gradle's Kotlin DSL task action.
 */
data class ComponentAuditRow(
    val packageName: String,
    val sourceFiles: Int,
    val testFiles: Int,
    val testCases: Int
)

fun componentPackageName(root: File, sourceFile: File): String =
    sourceFile.relativeTo(root).parentFile
        ?.invariantSeparatorsPath
        ?.replace("/", ".")
        .orEmpty()

/**
 * Every production Kotlin package must have a matching test package.
 * This is a package-level guard, not a claim that every method is covered;
 * use the Kover report for detailed line coverage.
 */
tasks.register("componentTestAudit") {
    group = "verification"
    description = "Checks that every production Kotlin package has matching tests and writes an audit report."

    doLast {
        val sourceRoot = file("src/main/kotlin")
        val testRoot = file("src/test/kotlin")
        val reportDirectory = layout.buildDirectory.dir("reports/quality").get().asFile
        reportDirectory.mkdirs()

        val sourceFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val testFiles = testRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val testAnnotation = Regex("""(?m)^\s*@(?:org\.junit\.jupiter\.api\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b""")
        val testsByPackage = testFiles.groupBy { componentPackageName(testRoot, it) }
        val sourcePackages = sourceFiles.groupBy { componentPackageName(sourceRoot, it) }.toSortedMap()

        val rows = sourcePackages.map { entry ->
            val pkg = entry.key
            val packageTests = testsByPackage[pkg].orEmpty()
            var testCases = 0
            packageTests.forEach { testFile ->
                testCases += testAnnotation.findAll(testFile.readText()).count()
            }
            ComponentAuditRow(pkg, entry.value.size, packageTests.size, testCases)
        }
        val uncovered = rows.filter { row -> row.testFiles == 0 || row.testCases == 0 }

        val report = StringBuilder()
        report.appendLine("# KSL component test audit")
        report.appendLine()
        report.appendLine("Package-level test presence only. A package test does not prove every method is covered.")
        report.appendLine("Use the Kover HTML report for line-level coverage and inspect individual test cases.")
        report.appendLine()
        report.appendLine("| Production package | Source files | Test files | Test cases | Status |")
        report.appendLine("|---|---:|---:|---:|---|")
        rows.forEach { row ->
            val status = if (row.testFiles > 0 && row.testCases > 0) "COVERED" else "MISSING"
            report.appendLine("| `${row.packageName}` | ${row.sourceFiles} | ${row.testFiles} | ${row.testCases} | $status |")
        }
        report.appendLine()
        report.appendLine("**Summary:** ${rows.size - uncovered.size}/${rows.size} packages have test files with recognized JUnit test cases.")
        if (uncovered.isNotEmpty()) {
            report.appendLine()
            report.appendLine("## Packages without executable test cases")
            uncovered.forEach { row ->
                report.appendLine("- `${row.packageName}` (${row.sourceFiles} production files, ${row.testFiles} test files, ${row.testCases} recognized test cases)")
            }
        }

        reportDirectory.resolve("component-test-audit.md").writeText(report.toString())
        logger.lifecycle("Component test audit: ${rows.size - uncovered.size}/${rows.size} production packages have recognized JUnit test cases.")

        if (uncovered.isNotEmpty()) {
            throw GradleException(
                "Packages missing test files or recognized JUnit test cases: " +
                    uncovered.joinToString { row -> row.packageName } +
                    ". See build/reports/quality/component-test-audit.md"
            )
        }
    }
}
