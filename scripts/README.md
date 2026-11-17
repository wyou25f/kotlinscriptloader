# KSL development tools

The scripts in this directory are local developer utilities. They do not publish releases or change the project version.

## Linux and macOS (Bash)

Use the native Bash entry points from the repository root:

| Tool | Purpose |
|---|---|
| ./build.sh -Task Build | Full clean build, tests, coverage and package audit |
| ./build.sh -Task Quality | Strict verification gate and artifact checks |
| ./build.sh -Task Package | Build and verify the plugin JAR |
| ./build.sh -Task Audit | Production-package test audit only |
| ./build.sh -Task Diagnose | Strict local environment check |
| ./scripts/test-build.sh --detailed | Test suite plus local reports |
| ./scripts/inspect-jar.sh | Inspect the newest JAR, or pass an explicit JAR path |
| ./scripts/diagnose-environment.sh | Print environment diagnostics (use --strict for a failing exit code) |
| ./scripts/collect-diagnostics.sh | Bundle local reports and test results |

All Bash tools use the Gradle wrapper through bash, so gradlew does not need its executable bit set. The build requires JDK 21. python3 is optional and is used only to generate the JSON/HTML dashboard; the log and text diagnostics still work without it. Diagnostics archiving requires either zip or Python 3.

Examples (run from the repository root):

    ./build.sh Build --no-daemon
    ./build.sh Quality --offline
    ./scripts/test-build.sh --detailed --open-report
    ./scripts/inspect-jar.sh build/libs/KotlinScriptLoader-1.2.1.jar
    ./scripts/collect-diagnostics.sh --include-build-output --run-environment-check

## Windows and PowerShell

PowerShell 7+ remains supported:

    ./build.ps1 -Task Build
    ./build.ps1 -Task Quality -NoDaemon
    ./scripts/Test-Build.ps1 -Detailed
    ./scripts/Inspect-Jar.ps1
    ./scripts/Collect-Diagnostics.ps1 -IncludeBuildOutput -RunEnvironmentCheck

The .ps1 and .sh entry points share task names and report locations. Bash scripts are native implementations rather than PowerShell compatibility wrappers.

## Reports and privacy

Build logs, JSON summaries, the HTML dashboard and diagnostics archives are written under build-reports/. Test reports remain under build/.

Review logs and archives before sharing them. They can contain local paths, usernames, server addresses, environment values, compiler output or snippets from build configuration. The tools do not automatically redact secrets.
