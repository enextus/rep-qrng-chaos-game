@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%make-chatgpt-context-zip.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

endlocal & pause & exit /b %EXIT_CODE%