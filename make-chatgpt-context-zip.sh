#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR_WIN="$(cygpath -w "$SCRIPT_DIR")"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PROJECT_DIR_WIN\\make-chatgpt-context-zip.ps1" "$@"
