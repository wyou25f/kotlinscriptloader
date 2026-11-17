#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/common.sh"

STRICT=0
while (($#)); do
  case "$1" in
    --strict|-Strict) STRICT=1; shift ;;
    --help|-h) printf 'Usage: %s [--strict]\n' "$0"; exit 0 ;;
    *) ksl_die "Unknown option: $1" ;;
  esac
done

REPORT_DIR="$(ksl_report_dir)"
REPORT_FILE="$REPORT_DIR/environment.txt"
: > "$REPORT_FILE"
FAILURES=0
WARNINGS=0

add_check() {
  local state="$1" name="$2" details="$3"
  printf '[%-4s] %s: %s\n' "$state" "$name" "$details"
  printf '%s | %s | %s\n' "$state" "$name" "$details" >> "$REPORT_FILE"
  if [[ "$state" == "FAIL" ]]; then FAILURES=$((FAILURES + 1)); fi
  if [[ "$state" == "WARN" ]]; then WARNINGS=$((WARNINGS + 1)); fi
  return 0
}

printf '\nKSL ENVIRONMENT DIAGNOSTICS\nRepository: %s\n' "$KSL_ROOT"
add_check "OK" "Bash" "$BASH_VERSION"
add_check "OK" "Operating system" "$(uname -srm 2>/dev/null || printf 'unknown')"

if command -v java >/dev/null 2>&1 || [[ -n "${JAVA_HOME:-}" ]]; then
  if JAVA_BIN="$(ksl_java_executable 2>/dev/null)"; then
    if JAVA_MAJOR="$(ksl_java_major "$JAVA_BIN" 2>/dev/null)"; then
      if [[ "$JAVA_MAJOR" == "21" ]]; then
        add_check "OK" "Java 21" "$JAVA_BIN"
      else
        add_check "FAIL" "Java 21" "Expected Java 21; detected Java $JAVA_MAJOR ($JAVA_BIN)"
      fi
    else
      add_check "FAIL" "Java executable" "Could not determine the Java version"
    fi
  else
    add_check "FAIL" "Java executable" "JAVA_HOME is invalid or java is missing from PATH"
  fi
else
  add_check "FAIL" "Java executable" "java was not found on PATH"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  if [[ -d "$JAVA_HOME" ]]; then add_check "OK" "JAVA_HOME" "$JAVA_HOME"
  else add_check "FAIL" "JAVA_HOME" "Directory does not exist: $JAVA_HOME"; fi
else
  add_check "WARN" "JAVA_HOME" "Not set; Gradle will use Java from PATH"
fi

if [[ -f "$KSL_ROOT/gradlew" ]]; then
  add_check "OK" "Gradle wrapper" "gradlew is present (invoked through bash; executable bit is not required)"
else
  add_check "FAIL" "Gradle wrapper" "gradlew is missing"
fi
PROPS="$KSL_ROOT/gradle/wrapper/gradle-wrapper.properties"
if [[ -f "$PROPS" ]]; then
  DISTRIBUTION="$(sed -n 's/^distributionUrl=//p' "$PROPS" | sed -n '1p')"
  add_check "OK" "Gradle distribution" "${DISTRIBUTION:-distribution URL is not declared}"
else
  add_check "FAIL" "Gradle wrapper config" "gradle/wrapper/gradle-wrapper.properties is missing"
fi

AVAILABLE_KB="$(df -Pk "$KSL_ROOT" 2>/dev/null | awk 'END { print $4 }')"
if [[ "$AVAILABLE_KB" =~ ^[0-9]+$ ]]; then
  AVAILABLE_GB="$(awk -v kb="$AVAILABLE_KB" 'BEGIN { printf "%.2f", kb / 1048576 }')"
  if (( AVAILABLE_KB < 1048576 )); then add_check "WARN" "Disk space" "$AVAILABLE_GB GB available"
  else add_check "OK" "Disk space" "$AVAILABLE_GB GB available"; fi
else
  add_check "WARN" "Disk space" "Could not read free disk space"
fi

for relative in build.gradle.kts settings.gradle.kts gradle.properties src/main/resources/plugin.yml; do
  if [[ -f "$KSL_ROOT/$relative" ]]; then add_check "OK" "File $relative" "present"
  else add_check "FAIL" "File $relative" "missing"; fi
done

if command -v git >/dev/null 2>&1; then
  BRANCH="$(git -C "$KSL_ROOT" branch --show-current 2>/dev/null || true)"
  COMMIT="$(git -C "$KSL_ROOT" rev-parse --short HEAD 2>/dev/null || true)"
  if [[ -n "$COMMIT" ]]; then add_check "OK" "Git" "${BRANCH:-detached} @ $COMMIT"
  else add_check "WARN" "Git" "Repository metadata is unavailable"; fi
else
  add_check "WARN" "Git" "Git executable not found; source-control details unavailable"
fi

if [[ "$FAILURES" -eq 0 ]]; then
  printf '\nEnvironment checks completed (%s warning(s)).\n' "$WARNINGS"
else
  printf '\n%s environment problem(s), %s warning(s) found.\n' "$FAILURES" "$WARNINGS" >&2
fi
printf 'Text report: %s\n' "$REPORT_FILE"

if command -v python3 >/dev/null 2>&1; then
  python3 - "$REPORT_FILE" "$REPORT_DIR/environment.json" "$KSL_ROOT" <<'PY'
import datetime, json, pathlib, sys
source, target, root = map(pathlib.Path, sys.argv[1:])
checks = []
for line in source.read_text(encoding="utf-8", errors="replace").splitlines():
    parts = line.split(" | ", 2)
    if len(parts) == 3:
        checks.append({"status": parts[0], "name": parts[1], "details": parts[2]})
problems = [f"{c['name']} - {c['details']}" for c in checks if c["status"] == "FAIL"]
report = {
    "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    "repository": str(root),
    "checks": checks,
    "problems": problems,
}
target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"JSON report: {target}")
PY
fi

if [[ "$STRICT" -eq 1 && "$FAILURES" -gt 0 ]]; then exit 1; fi
exit 0
