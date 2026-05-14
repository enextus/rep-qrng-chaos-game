#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

if command -v cygpath >/dev/null 2>&1; then
    SCRIPT_DIR_FOR_POWERSHELL="$(cygpath -w "$SCRIPT_DIR")"
    SCRIPT_PATH="$SCRIPT_DIR_FOR_POWERSHELL\\make-chatgpt-context-zip.ps1"
else
    SCRIPT_DIR_FOR_POWERSHELL="$SCRIPT_DIR"
    SCRIPT_PATH="$SCRIPT_DIR_FOR_POWERSHELL/make-chatgpt-context-zip.ps1"
fi

if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_PATH" "$@"
elif command -v pwsh >/dev/null 2>&1; then
    pwsh -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_PATH" "$@"
elif command -v powershell >/dev/null 2>&1; then
    powershell -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_PATH" "$@"
else
    echo "PowerShell was not found. Install PowerShell or run make-chatgpt-context-zip.cmd on Windows." >&2
    exit 1
fi
