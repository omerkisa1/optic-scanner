@echo off
setlocal

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run-fresh-emulator.ps1" %*
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
  echo.
  echo Script failed with exit code %EXIT_CODE%.
  echo Check output above for the failing step.
  pause
  exit /b %EXIT_CODE%
)

echo.
echo Completed successfully.
pause
