@echo off
setlocal
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

echo Setting up adb forward tcp:18765 -> phone ...
"%ADB%" forward tcp:18765 tcp:18765
if errorlevel 1 (
  echo adb forward failed - is the phone connected?
  pause
  exit /b 1
)

start "SAM viz server" /MIN cmd /c "python "%~dp0server.py""
timeout /t 2 >nul
start http://127.0.0.1:8765
endlocal
