#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/common.sh"

REPORT_NAME="build"
NO_DAEMON=0
OFFLINE=0
DETAILED=0
declare -a GRADLE_TASKS=()
while (($#)); do
  case "$1" in
    --report-name)
      (($# >= 2)) || ksl_die "Missing report name."
      REPORT_NAME="$2"; shift 2 ;;
    --no-daemon) NO_DAEMON=1; shift ;;
    --offline) OFFLINE=1; shift ;;
    --detailed) DETAILED=1; shift ;;
    --) shift; GRADLE_TASKS=("$@"); break ;;
    *) ksl_die "Unknown option: $1 (put Gradle tasks after --)" ;;
  esac
done
[[ "$REPORT_NAME" =~ ^[a-zA-Z0-9._-]+$ ]] || ksl_die "Invalid report name: $REPORT_NAME"
if (("${#GRADLE_TASKS[@]}" == 0)); then GRADLE_TASKS=(build); fi

REPORT_DIR="$(ksl_report_dir)"
LOG_DIR="$REPORT_DIR/logs"
mkdir -p "$LOG_DIR"
STAMP="$(date '+%Y%m%d-%H%M%S')"
LOG_PATH="$LOG_DIR/$REPORT_NAME-$STAMP.log"
WRAPPER="$KSL_ROOT/gradlew"
[[ -f "$WRAPPER" ]] || ksl_die "Gradle wrapper not found: $WRAPPER"
ksl_require_command bash

declare -a ARGUMENTS=(--console=plain --stacktrace)
if [[ "$NO_DAEMON" -eq 1 ]]; then ARGUMENTS+=(--no-daemon); fi
if [[ "$OFFLINE" -eq 1 ]]; then ARGUMENTS+=(--offline); fi
if [[ "$DETAILED" -eq 1 ]]; then ARGUMENTS+=(--info); fi
ARGUMENTS+=("${GRADLE_TASKS[@]}")

printf 'KSL build diagnostics\n'
printf 'Command: bash gradlew'
printf ' %q' "${ARGUMENTS[@]}"
printf '\nLog: %s\n\n' "$LOG_PATH"

START_EPOCH="$(date +%s)"
set +e
(
  cd "$KSL_ROOT"
  bash "$WRAPPER" "${ARGUMENTS[@]}"
) 2>&1 | tee "$LOG_PATH"
EXIT_CODE="${PIPESTATUS[0]}"
set -e
FINISH_EPOCH="$(date +%s)"
DURATION="$((FINISH_EPOCH - START_EPOCH))"

export KSL_REPORT_DIR="$REPORT_DIR"
export KSL_REPORT_NAME="$REPORT_NAME"
export KSL_EXIT_CODE="$EXIT_CODE"
export KSL_START_EPOCH="$START_EPOCH"
export KSL_FINISH_EPOCH="$FINISH_EPOCH"
export KSL_DURATION="$DURATION"
export KSL_LOG_PATH="$LOG_PATH"
export KSL_ROOT
export KSL_GRADLE_TASKS="$(printf '%s\n' "${GRADLE_TASKS[@]}")"

if command -v python3 >/dev/null 2>&1; then
  if python3 - <<'PY'
import datetime
import json
import os
import pathlib
import platform
import re
import shutil

root = pathlib.Path(os.environ["KSL_ROOT"])
report_dir = pathlib.Path(os.environ["KSL_REPORT_DIR"])
log_path = pathlib.Path(os.environ["KSL_LOG_PATH"])
try:
    output = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
except OSError:
    output = []

exit_code = int(os.environ["KSL_EXIT_CODE"])
started = int(os.environ["KSL_START_EPOCH"])
finished = int(os.environ["KSL_FINISH_EPOCH"])
def local_time(epoch):
    return datetime.datetime.fromtimestamp(epoch).astimezone().isoformat(timespec="seconds")

failure_pattern = re.compile(
    r"(?i)(^e: file:|\.kt:\d+:\d+: error:|what went wrong|caused by:|execution failed|"
    r"could not resolve|could not find|unresolved reference| FAILED$|exception:|"
    r"assertionerror|tests completed, \d+ failed)"
)
report = {
    "project": "KotlinScriptLoader",
    "task": os.environ["KSL_REPORT_NAME"],
    "status": "success" if exit_code == 0 else "failed",
    "exitCode": exit_code,
    "startedAt": local_time(started),
    "finishedAt": local_time(finished),
    "durationSeconds": round(float(os.environ["KSL_DURATION"]), 2),
    "machine": platform.node(),
    "operatingSystem": platform.platform(),
    "runner": f"Bash {os.environ.get('BASH_VERSION', 'unknown')}",
    "javaHome": os.environ.get("JAVA_HOME", ""),
    "gradleTasks": [item for item in os.environ.get("KSL_GRADLE_TASKS", "").splitlines() if item],
    "logFile": log_path.relative_to(root).as_posix(),
    "taskOutput": [line for line in output if line.startswith("> Task ")],
    "failures": [line for line in output if failure_pattern.search(line)][-80:],
    "testSummary": [line for line in output if re.search(r"(?i)tests completed|tests failed|TOTAL:", line)][-10:],
}
json_path = report_dir / "latest.json"
json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
template = root / "scripts" / "Build-Report.html"
css = root / "scripts" / "Build-Report.css"
if template.is_file() and css.is_file():
    html = template.read_text(encoding="utf-8").replace(
        "__KSL_REPORT_JSON__", json.dumps(report, ensure_ascii=False).replace("</", "<\\/")
    )
    (report_dir / "index.html").write_text(html, encoding="utf-8")
    shutil.copy2(css, report_dir / "Build-Report.css")
else:
    print("WARNING: HTML report template or stylesheet is missing; JSON report was written.")
print(f"Report: {report_dir / 'index.html'}")
print(f"JSON:   {json_path}")
PY
  then
    :
  else
    ksl_warn "Could not generate JSON/HTML report; the Gradle log is still available."
  fi
else
  ksl_warn "python3 is not installed; the log was saved but JSON/HTML reports were skipped. Install Python 3 for rich local reports."
fi

printf '\n'
if [[ "$EXIT_CODE" -eq 0 ]]; then
  printf 'BUILD SUCCEEDED in %s sec\n' "$DURATION"
else
  printf 'BUILD FAILED (exit %s) after %s sec\n' "$EXIT_CODE" "$DURATION" >&2
  if [[ -f "$LOG_PATH" ]]; then
    printf 'Detected failure details:\n' >&2
    grep -Ei '(^e: file:|\.kt:[0-9]+:[0-9]+: error:|what went wrong|caused by:|execution failed|could not resolve|could not find|unresolved reference| FAILED$|exception:|assertionerror|tests completed, [0-9]+ failed)' "$LOG_PATH" | tail -n 80 >&2 || true
  fi
fi
printf 'Log: %s\n' "$LOG_PATH"
exit "$EXIT_CODE"
