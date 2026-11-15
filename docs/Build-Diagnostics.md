# KSL Build Diagnostics

The repository root contains a single entry point: **build.ps1**. It runs Gradle through the wrapper, saves the full log, and generates a local HTML report.

## Requirements

- JDK 21;
- PowerShell 7+ (pwsh);
- access to Maven Central and PaperMC repositories during the first build.

The plugin targets Paper 1.21.1 / Java 21. Gradle is installed through the wrapper.

## Quick start

~~~powershell
./build.ps1 -Task Build
~~~

## Commands

| Command | Purpose |
|---|---|
| ./build.ps1 -Task Build | Clean full build, tests, and JAR verification |
| ./build.ps1 -Task Test | Clean test run |
| ./build.ps1 -Task Package | Build the JAR and check its contents without rerunning tests |
| ./build.ps1 -Task Verify | Run project checks and JAR verification |
| ./build.ps1 -Task Diagnose | Check Java, JAVA_HOME, Gradle wrapper, disk, Git, and project structure |
| ./build.ps1 -Task Ide | Check the IntelliJ IDEA companion project |
| ./build.ps1 -Task Clean | Remove Gradle build outputs |
| ./build.ps1 -Task Release | Build a complete release candidate |

Additional options:

- -NoDaemon — do not leave the Gradle daemon running;
- -Offline — use only previously downloaded dependencies;
- -Detailed — enable verbose Gradle --info logging.

## Standalone tools

Scripts are in scripts/ and can be run independently:

| Script | Purpose |
|---|---|
| scripts/Diagnose-Environment.ps1 | Local environment diagnostics |
| scripts/Debug-Build.ps1 | Run a Gradle task with logs, stack trace, and report |
| scripts/Test-Build.ps1 | Run tests and provide links to HTML/JUnit reports |
| scripts/Inspect-Jar.ps1 | Check plugin.yml, main class, and required classes in the JAR |
| scripts/Collect-Diagnostics.ps1 | Collect logs and reports into a ZIP archive |
| scripts/Build-Report.html | HTML report template |
| scripts/Build-Report.css | Report styling |

## Where to find results

- build-reports/index.html — visual summary: status, duration, Gradle tasks, errors, test summary, and environment;
- build-reports/latest.json — structured report data;
- build-reports/logs/ — full console output with timestamp;
- build/reports/tests/test/index.html — Gradle HTML test report;
- build/test-results/test/ — JUnit XML;
- build-reports/environment.json — environment check results;
- build-reports/jar-inspection.json — latest JAR inspection results.

The HTML report is self-contained: JSON data is embedded in the generated page. Open build-reports/index.html in a modern browser; no local web server is required.

## Reading errors

1. Start with the failed status and exit code.
2. In “Gradle stages”, find the first task marked FAILED.
3. In “Errors”, look for the first meaningful line beginning with e:, What went wrong, Caused by, or AssertionError. Later errors are often consequences of the first.
4. If tests failed, inspect build/reports/tests/test/index.html and the JUnit XML. KSL also prints internal self-check names in test stdout.
5. For dependency failures, attach the full log: it contains the stack trace and Gradle coordinates/repository error.

Deprecated warnings alone do not mean the build failed. Look for the first actual error and the final exit code.

## Server watchdog diagnostics

KSL does not disable or replace Paper Watchdog. When ticks stop, KSL can save plugins/KotlinScriptLoader/dumps/watchdog-*.txt. A report contains multiple thread snapshots; compare MAIN THREAD between snapshots. Important sections include JVM/heap/GC state, lock owner, locked monitors/synchronizers, detected deadlocks, and database queues. Snapshot count is set by diagnostics.watchdog.snapshot-count (1–5), and the interval by snapshot-interval-ms (250–2000). Thread and stack-frame limits prevent diagnostic files from growing without bounds. The /ksl watchdog command shows the latest dump path.

## CI

GitHub Actions runs the same PowerShell commands available locally. The diagnostics artifact is uploaded with if: always(), so test reports and logs are preserved even when the build fails. The JAR is built in a separate stage only after tests succeed.
