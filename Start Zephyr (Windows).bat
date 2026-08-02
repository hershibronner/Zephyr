@echo off
REM Double-click this file to run Zephyr on your own machine.

cd /d "%~dp0"

echo.
echo   Starting Zephyr...
echo.

where node >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
  echo   Node.js isn't installed yet - Zephyr needs it to run a local server.
  echo.
  echo   Opening the download page. Get the button marked LTS, run the installer,
  echo   then double-click this file again.
  echo.
  start "" "https://nodejs.org/en/download/prebuilt-installer"
  echo   Press any key to close this window.
  pause >nul
  exit /b 1
)

node prototype\serve.mjs

REM Keeps the window up if the server exits, so any error stays readable instead of vanishing.
echo.
echo   Zephyr has stopped. Press any key to close this window.
pause >nul
