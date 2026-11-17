#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/common.sh"

JAR_PATH=""
while (($#)); do
  case "$1" in
    --help|-h) printf 'Usage: %s [path/to/plugin.jar]\n' "$0"; exit 0 ;;
    -*) ksl_die "Unknown option: $1" ;;
    *) [[ -z "$JAR_PATH" ]] || ksl_die "Only one JAR path may be supplied."; JAR_PATH="$1"; shift ;;
  esac
done

if [[ -n "$JAR_PATH" && ! -f "$JAR_PATH" ]]; then
  if [[ "$JAR_PATH" != /* && -f "$PWD/$JAR_PATH" ]]; then JAR_PATH="$PWD/$JAR_PATH"
  else ksl_die "JAR file not found: $JAR_PATH"; fi
fi

if [[ -z "$JAR_PATH" ]]; then
  for candidate in "$KSL_ROOT"/build/libs/*.jar; do
    [[ -f "$candidate" ]] || continue
    case "$(basename "$candidate")" in *-sources.jar|-javadoc.jar) continue ;; esac
    if [[ -z "$JAR_PATH" || "$candidate" -nt "$JAR_PATH" ]]; then JAR_PATH="$candidate"; fi
  done
fi
[[ -n "$JAR_PATH" ]] || ksl_die "No JAR found in build/libs. Run ./build.sh Build first."
[[ "$JAR_PATH" == /* ]] || JAR_PATH="$PWD/$JAR_PATH"
JAR_PATH="$(cd -- "$(dirname -- "$JAR_PATH")" && pwd -P)/$(basename -- "$JAR_PATH")"

ksl_require_command jar
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ksl-jar-inspect.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT
ENTRIES="$TMP_DIR/entries.txt"
jar tf "$JAR_PATH" > "$ENTRIES" || ksl_die "Could not read JAR archive: $JAR_PATH"
SIZE="$(ksl_file_size "$JAR_PATH")"
ENTRY_COUNT="$(wc -l < "$ENTRIES" | tr -d '[:space:]')"
KOTLIN_COUNT="$(grep -Ec '^(kotlin|kotlinx)/' "$ENTRIES" || true)"
ISSUES=0
MAIN_PRESENT=0
PLUGIN_PRESENT=0
declare -a ISSUE_LIST=()
issue() { printf '[FAIL] %s\n' "$1"; ISSUE_LIST+=("$1"); ISSUES=$((ISSUES + 1)); }
ok() { printf '[OK] %s\n' "$1"; }
has_entry() { grep -Fxq -- "$1" "$ENTRIES"; }

printf '\nKSL JAR INSPECTOR\nFile: %s\nSize: %.2f MB | Entries: %s\nKotlin runtime entries: %s\n' \
  "$JAR_PATH" "$(awk -v bytes="$SIZE" 'BEGIN { printf "%.2f", bytes / 1048576 }')" "$ENTRY_COUNT" "$KOTLIN_COUNT"

MAIN_CLASS="ru/privateserver/ksl/KotlinScriptLoaderPlugin.class"
if has_entry "$MAIN_CLASS"; then MAIN_PRESENT=1; ok "Main class exists"; else issue "Main class missing"; fi
if has_entry "plugin.yml"; then
  PLUGIN_PRESENT=1
  (cd "$TMP_DIR" && jar xf "$JAR_PATH" plugin.yml) || issue "Could not extract plugin.yml"
  YAML="$TMP_DIR/plugin.yml"
  if [[ -f "$YAML" ]]; then
    ok "plugin.yml exists"
    grep -Eq '^main:[[:space:]]*ru\.privateserver\.ksl\.KotlinScriptLoaderPlugin[[:space:]]*$' "$YAML" ||
      issue "plugin.yml main entry is missing or unexpected"
    grep -Eq "^api-version:[[:space:]]*['\"]?1\.21" "$YAML" ||
      issue "plugin.yml api-version does not appear to target 1.21"
  fi
else
  issue "plugin.yml missing"
fi

REQUIRED=(
  "ru/privateserver/ksl/api/KSLApiProvider.class"
  "ru/privateserver/ksl/api/KSLPublicApiKt.class"
  "ru/privateserver/ksl/api/KSLLogger.class"
  "ru/privateserver/ksl/api/KSLLogLevel.class"
  "ru/privateserver/ksl/api/KSLLoggerConfig.class"
  "ru/privateserver/ksl/rt/KSLCache.class"
  "ru/privateserver/ksl/rt/KSLProfiler.class"
  "ru/privateserver/ksl/rt/KSLRuntimeScope.class"
  "ru/privateserver/ksl/KSLQueuePressureMonitor.class"
  "ru/privateserver/ksl/KSLDatabaseSettings.class"
  "ru/privateserver/ksl/KSLDatabaseExecutionPolicy.class"
  "ru/privateserver/ksl/KSLDatabaseSession.class"
  "ru/privateserver/ksl/nbt/KSLNBTEngine.class"
  "ru/privateserver/ksl/nbt/KSLNBTDocument.class"
  "ru/privateserver/ksl/nbt/KSLNBTLimits.class"
  "ru/privateserver/ksl/nbt/KSLNBTSnbtPolicy.class"
)
for entry in "${REQUIRED[@]}"; do
  if has_entry "$entry"; then ok "$entry"; else issue "Required entry missing: $entry"; fi
done

if [[ -f "$TMP_DIR/plugin.yml" ]]; then
  if grep -Eq '^libraries:[[:space:]]*\[' "$TMP_DIR/plugin.yml" &&
     ! grep -Eq '^libraries:[[:space:]]*\[[[:space:]]*\]' "$TMP_DIR/plugin.yml"; then
    if (( KOTLIN_COUNT > 0 )); then issue "JAR embeds Kotlin runtime but plugin.yml also declares external Kotlin libraries"
    else ok "Thin-JAR dependency declaration found"; fi
  elif (( KOTLIN_COUNT > 0 )); then
    ok "Embedded Kotlin runtime found (fat JAR)"
  else
    issue "Neither embedded Kotlin runtime nor external Paper libraries were detected"
  fi
fi

REPORT_DIR="$(ksl_report_dir)"
if command -v python3 >/dev/null 2>&1; then
  export KSL_JAR_PATH="$JAR_PATH" KSL_JAR_SIZE="$SIZE" KSL_ENTRY_COUNT="$ENTRY_COUNT"
  export KSL_KOTLIN_COUNT="$KOTLIN_COUNT" KSL_ISSUE_COUNT="$ISSUES"
  export KSL_MAIN_PRESENT="$MAIN_PRESENT" KSL_PLUGIN_PRESENT="$PLUGIN_PRESENT"
  export KSL_ISSUES="$(printf '%s\n' "${ISSUE_LIST[@]}")"
  if python3 - "$REPORT_DIR/jar-inspection.json" <<'PY'
import datetime, json, os, pathlib, sys
target = pathlib.Path(sys.argv[1])
jar = pathlib.Path(os.environ["KSL_JAR_PATH"])
report = {
    "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    "jar": str(jar),
    "details": [{
        "file": jar.name,
        "sizeBytes": int(os.environ["KSL_JAR_SIZE"]),
        "entries": int(os.environ["KSL_ENTRY_COUNT"]),
        "kotlinRuntimeEntries": int(os.environ["KSL_KOTLIN_COUNT"]),
        "hasPluginDescriptor": os.environ["KSL_PLUGIN_PRESENT"] == "1",
        "hasMainClass": os.environ["KSL_MAIN_PRESENT"] == "1",
    }],
    "issueCount": int(os.environ["KSL_ISSUE_COUNT"]),
    "issues": [line for line in os.environ.get("KSL_ISSUES", "").splitlines() if line],
}
target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
  then :; else ksl_warn "Could not write the JAR inspection JSON report."; fi
else
  ksl_warn "python3 is not installed; JSON report was skipped."
fi

if (( ISSUES > 0 )); then
  printf '\n%s issue(s) detected.\n' "$ISSUES" >&2
  if (("${#ISSUE_LIST[@]}" > 0)); then printf ' - %s\n' "${ISSUE_LIST[@]}" >&2; fi
  exit 1
fi
printf '\nJAR inspection passed.\n'
