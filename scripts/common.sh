#!/usr/bin/env bash
# Shared helpers for the native KSL Bash tools. Source this file; do not execute it.
KSL_SCRIPTS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
KSL_ROOT="$(cd -- "$KSL_SCRIPTS_DIR/.." && pwd -P)"
KSL_EXPECTED_VERSION="1.2.2"

ksl_die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

ksl_warn() {
  printf 'WARNING: %s\n' "$*" >&2
}

ksl_require_command() {
  command -v "$1" >/dev/null 2>&1 || ksl_die "Required command not found: $1"
}

ksl_require_file() {
  [[ -f "$KSL_ROOT/$1" ]] || ksl_die "Required project file is missing: $1"
}

ksl_java_executable() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    [[ -d "$JAVA_HOME" ]] || ksl_die "JAVA_HOME points to a missing directory: $JAVA_HOME"
    if [[ -x "$JAVA_HOME/bin/java" ]]; then
      printf '%s\n' "$JAVA_HOME/bin/java"
    elif [[ -x "$JAVA_HOME/bin/java.exe" ]]; then
      printf '%s\n' "$JAVA_HOME/bin/java.exe"
    else
      ksl_die "JAVA_HOME does not contain an executable bin/java: $JAVA_HOME"
    fi
  else
    command -v java || ksl_die "Java was not found on PATH. Install JDK 21 and set JAVA_HOME or PATH."
  fi
}

ksl_java_major() {
  local java_bin="$1"
  local output first_line
  output="$("$java_bin" -version 2>&1)" || ksl_die "Could not run Java: $java_bin"
  first_line="$(printf '%s\n' "$output" | sed -n '1p')"
  if [[ "$first_line" =~ version[[:space:]]+\"([0-9]+) ]] || [[ "$first_line" =~ openjdk[[:space:]]+([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    ksl_die "Could not parse Java version output: $first_line"
  fi
}

ksl_check_java_21() {
  local java_bin major
  java_bin="$(ksl_java_executable)"
  major="$(ksl_java_major "$java_bin")"
  [[ "$major" == "21" ]] || ksl_die "KSL requires Java 21; detected Java $major at $java_bin"
  printf 'Java 21: %s\n' "$java_bin"
}

ksl_project_version() {
  sed -nE 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$KSL_ROOT/build.gradle.kts" | sed -n '1p'
}

ksl_check_project_version() {
  local version
  version="$(ksl_project_version)"
  [[ -n "$version" ]] || ksl_die "Could not determine project version from build.gradle.kts."
  [[ "$version" == "$KSL_EXPECTED_VERSION" ]] || ksl_die "Build tooling expects version $KSL_EXPECTED_VERSION; found $version. Keep the unpublished 1.2.2 project version unchanged."
  printf 'Project version: %s\n' "$version"
}

ksl_file_size() {
  wc -c < "$1" | tr -d '[:space:]'
}

ksl_report_dir() {
  mkdir -p "$KSL_ROOT/build-reports"
  printf '%s\n' "$KSL_ROOT/build-reports"
}
