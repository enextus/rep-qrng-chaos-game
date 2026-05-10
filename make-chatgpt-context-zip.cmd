@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-chatgpt-context-zip.ps1" %*
endlocal
