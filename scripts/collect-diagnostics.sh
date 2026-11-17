#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/common.sh"

INCLUDE_BUILD_OUTPUT=0
RUN_ENV_CHECK=0
while (($#)); do
  case "$1" in
    --include-build-output|-IncludeBuildOutput) INCLUDE_BUILD_OUTPUT=1; shift ;;
    --run-environment-check|-RunEnvironmentCheck) RUN_ENV_CHECK=1; shift ;;
    --help|-h) printf 'Usage: %s [--include-build-output] [--run-environment-check]\n' "$0"; exit 0 ;;
    *) ksl_die "Unknown option: $1" ;;
  esac
done

REPORT_DIR="$(ksl_report_dir)"
if [[ "$RUN_ENV_CHECK" -eq 1 ]]; then
  "$SCRIPT_DIR/diagnose-environment.sh"
fi

INSPECT_FAILED=0
if [[ "$INCLUDE_BUILD_OUTPUT" -eq 1 && -d "$KSL_ROOT/build/libs" ]]; then
  set +e
  "$SCRIPT_DIR/inspect-jar.sh"
  INSPECT_CODE=$?
  set -e
  [[ "$INSPECT_CODE" -eq 0 ]] || INSPECT_FAILED=1
fi

STAMP="$(date '+%Y%m%d-%H%M%S')"
ARCHIVE="$REPORT_DIR/KSL-diagnostics-$STAMP.zip"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/ksl-diagnostics.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

copy_tree() {
  local source="$1" name="$2"
  [[ -d "$source" ]] || return 0
  mkdir -p "$STAGE/$name"
  (cd "$source" && find . -type f ! -name 'KSL-diagnostics-*.zip' -print0) |
    while IFS= read -r -d '' relative; do
      mkdir -p "$STAGE/$name/$(dirname "$relative")"
      cp -p "$source/$relative" "$STAGE/$name/$relative"
    done
}

copy_tree "$REPORT_DIR" "build-reports"
copy_tree "$KSL_ROOT/build/reports/tests/test" "gradle-test-html"
copy_tree "$KSL_ROOT/build/test-results/test" "junit-xml"

if ! find "$STAGE" -type f -print -quit | grep -q .; then
  ksl_die "No diagnostics found. Run ./build.sh -Task Test or ./build.sh -Task Build first."
fi

if command -v zip >/dev/null 2>&1; then
  (cd "$STAGE" && zip -qr "$ARCHIVE" .)
elif command -v python3 >/dev/null 2>&1; then
  python3 - "$STAGE" "$ARCHIVE" <<'PY'
import pathlib, sys, zipfile
stage, archive = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    for path in stage.rglob("*"):
        if path.is_file():
            zf.write(path, path.relative_to(stage).as_posix())
PY
else
  ksl_die "Install 'zip' or Python 3 to create the diagnostics archive."
fi

printf '\nDiagnostics archive created:\n%s\n' "$ARCHIVE"
ksl_warn "Review the archive before sharing it; logs may contain local paths, usernames, IPs, or environment details."
if [[ "$INSPECT_FAILED" -eq 1 ]]; then
  ksl_warn "JAR inspection found issues; its report and diagnostics were still archived."
  exit 1
fi
