#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/scripts/common.sh"

usage() {
  cat <<'USAGE'
KotlinScriptLoader build controller

Usage:
  ./build.sh [Build|Test|Verify|Quality|Audit|Package|Clean|Ide|Release|Diagnose] [options]
  ./build.sh --task Quality --no-daemon --detailed

Tasks:
  Build      Clean build, tests, coverage reports and component audit
  Test       Clean test run, coverage reports and component audit
  Verify     Full verification plus JAR checks
  Quality    Alias for Verify
  Audit      Run only the production-package test audit
  Package    Run checks and assemble the plugin JAR
  Clean      Remove Gradle build outputs
  Ide        Verify the IntelliJ IDEA companion setup (requires a built JAR)
  Release    Run the full release-quality build locally; does not publish a release
  Diagnose   Check the local build environment

Options:
  --task, -Task NAME     Select a task (PowerShell-style -Task is accepted)
  --no-daemon            Disable the Gradle daemon
  --offline              Use Gradle offline mode
  --detailed             Add Gradle --info output
  --skip-preflight       Skip the Java 21 preflight check
  --help, -h             Show this help

Java 21 is required. Build logs and reports are written to build-reports/.
The project version guard pins this maintenance line to 1.2.1.
USAGE
}

TASK="Build"
TASK_SET=0
NO_DAEMON=0
OFFLINE=0
DETAILED=0
SKIP_PREFLIGHT=0

while (($#)); do
  case "$1" in
    --task|-Task|-t)
      (($# >= 2)) || { usage >&2; ksl_die "Missing task name after $1"; }
      TASK="$2"; TASK_SET=1; shift 2 ;;
    --no-daemon|-NoDaemon)
      NO_DAEMON=1; shift ;;
    --offline|-Offline)
      OFFLINE=1; shift ;;
    --detailed|-Detailed)
      DETAILED=1; shift ;;
    --skip-preflight|-SkipPreflight)
      SKIP_PREFLIGHT=1; shift ;;
    --help|-h)
      usage; exit 0 ;;
    -*)
      usage >&2; ksl_die "Unknown option: $1" ;;
    *)
      if [[ "$TASK_SET" -eq 0 && "$TASK" == "Build" ]]; then
        TASK="$1"; TASK_SET=1; shift
      else
        usage >&2; ksl_die "Unexpected argument: $1"
      fi ;;
  esac
done

if [[ "$TASK" == "Diagnose" ]]; then
  exec "$SCRIPT_DIR/scripts/diagnose-environment.sh" --strict
fi

case "$TASK" in
  Build|Test|Verify|Quality|Audit|Package|Clean|Ide|Release) ;;
  *) usage >&2; ksl_die "Unsupported task: $TASK" ;;
esac

for required in \
  settings.gradle.kts build.gradle.kts gradle/wrapper/gradle-wrapper.properties \
  src/main/resources/plugin.yml gradlew scripts/common.sh scripts/debug-build.sh \
  scripts/diagnose-environment.sh scripts/Build-Report.html scripts/Build-Report.css; do
  ksl_require_file "$required"
done

if [[ "$SKIP_PREFLIGHT" -eq 0 ]]; then
  ksl_check_java_21
fi
ksl_check_project_version

VERSION="$(ksl_project_version)"
declare -a GRADLE_TASKS
case "$TASK" in
  Build)   GRADLE_TASKS=(clean build koverVerify koverHtmlReport koverXmlReport componentTestAudit) ;;
  Test)    GRADLE_TASKS=(clean test koverVerify koverHtmlReport koverXmlReport componentTestAudit) ;;
  Verify|Quality)
           GRADLE_TASKS=(clean check koverVerify koverHtmlReport koverXmlReport componentTestAudit verifyShadowJar) ;;
  Audit)   GRADLE_TASKS=(componentTestAudit) ;;
  Package) GRADLE_TASKS=(check assemble koverVerify koverHtmlReport koverXmlReport componentTestAudit verifyShadowJar) ;;
  Clean)   GRADLE_TASKS=(clean) ;;
  Ide)
    JAR="$KSL_ROOT/build/libs/KotlinScriptLoader-$VERSION.jar"
    [[ -f "$JAR" ]] || ksl_die "IDE verification requires the built JAR: $JAR. Run ./build.sh Build first."
    GRADLE_TASKS=(-p ide "-PkslJar=../build/libs/KotlinScriptLoader-$VERSION.jar" verifyKslIdeSetup)
    ;;
  Release) GRADLE_TASKS=(clean build koverVerify koverHtmlReport koverXmlReport componentTestAudit verifyShadowJar) ;;
esac

printf '\n  K O T L I N S C R I P T L O A D E R\n'
printf '  Native Bash build controller\n  Version: %s\n  Task: %s\n\n' "$VERSION" "$TASK"

REPORT_NAME="$(printf '%s' "$TASK" | tr '[:upper:]' '[:lower:]')"
if [[ "$TASK" == "Package" ]]; then REPORT_NAME="build"; fi
declare -a DEBUG_ARGS=(--report-name "$REPORT_NAME")
if [[ "$NO_DAEMON" -eq 1 ]]; then DEBUG_ARGS+=(--no-daemon); fi
if [[ "$OFFLINE" -eq 1 ]]; then DEBUG_ARGS+=(--offline); fi
if [[ "$DETAILED" -eq 1 ]]; then DEBUG_ARGS+=(--detailed); fi

"$SCRIPT_DIR/scripts/debug-build.sh" "${DEBUG_ARGS[@]}" -- "${GRADLE_TASKS[@]}"

if [[ "$TASK" =~ ^(Build|Verify|Quality|Package|Release)$ ]]; then
  EXPECTED_JAR="$KSL_ROOT/build/libs/KotlinScriptLoader-$VERSION.jar"
  [[ -f "$EXPECTED_JAR" ]] || ksl_die "Gradle succeeded but the expected plugin JAR is missing: $EXPECTED_JAR"
  SIZE="$(ksl_file_size "$EXPECTED_JAR")"
  (( SIZE >= 1048576 )) || ksl_die "Generated plugin JAR is suspiciously small ($SIZE bytes): $EXPECTED_JAR"
  printf 'Strict artifact check passed: %s (%s bytes).\n' "$(basename "$EXPECTED_JAR")" "$SIZE"
fi

if [[ "$TASK" =~ ^(Build|Verify|Quality|Package|Release)$ ]]; then
  [[ -f "$KSL_ROOT/build/reports/kover/html/index.html" ]] || ksl_die "Kover coverage report is missing."
  [[ -f "$KSL_ROOT/build/reports/quality/component-test-audit.md" ]] || ksl_die "Component test audit report is missing."
  printf 'Quality reports generated: Kover coverage + component test audit.\n'
fi

exit 0
