#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/common.sh"

DETAILED=0
NO_DAEMON=0
OFFLINE=0
OPEN_REPORT=0
while (($#)); do
  case "$1" in
    --detailed|-Detailed) DETAILED=1; shift ;;
    --no-daemon|-NoDaemon) NO_DAEMON=1; shift ;;
    --offline|-Offline) OFFLINE=1; shift ;;
    --open-report|-OpenReport) OPEN_REPORT=1; shift ;;
    --help|-h) printf 'Usage: %s [--detailed] [--no-daemon] [--offline] [--open-report]\n' "$0"; exit 0 ;;
    *) ksl_die "Unknown option: $1" ;;
  esac
done

DEBUG_ARGS=(--report-name verify)
if [[ "$DETAILED" -eq 1 ]]; then DEBUG_ARGS+=(--detailed); fi
if [[ "$NO_DAEMON" -eq 1 ]]; then DEBUG_ARGS+=(--no-daemon); fi
if [[ "$OFFLINE" -eq 1 ]]; then DEBUG_ARGS+=(--offline); fi

set +e
"$SCRIPT_DIR/debug-build.sh" "${DEBUG_ARGS[@]}" -- clean check koverHtmlReport koverXmlReport componentTestAudit
CODE=$?
set -e

HTML="$KSL_ROOT/build/reports/tests/test/index.html"
REPORT="$KSL_ROOT/build-reports/index.html"
XML="$KSL_ROOT/build/test-results/test"
printf '\nTEST OUTPUTS\n'
[[ -f "$HTML" ]] && printf 'Gradle test report: %s\n' "$HTML"
[[ -d "$XML" ]] && printf 'JUnit XML results:  %s\n' "$XML"
[[ -f "$REPORT" ]] && printf 'KSL build report:   %s\n' "$REPORT"

if [[ "$OPEN_REPORT" -eq 1 && -f "$REPORT" ]]; then
  if command -v xdg-open >/dev/null 2>&1; then xdg-open "$REPORT" >/dev/null 2>&1 &
  elif command -v open >/dev/null 2>&1; then open "$REPORT"
  else ksl_warn "No supported desktop opener found; open this file manually: $REPORT"; fi
fi
exit "$CODE"
