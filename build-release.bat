@echo off
rem Produce the runnable artifact used by the desktop shortcut.
setlocal
cd /d "%~dp0"

call mvn -q clean package
if errorlevel 1 (
  echo Release build failed.
  exit /b 1
)

if not exist "dist" mkdir "dist"
copy /y "app\target\threat-detector-java-1.0.0-all.jar" "dist\ThreatDetector.jar" >nul
copy /y "app\src\main\resources\icons\icon.ico" "dist\icon.ico" >nul

echo Release ready: "%CD%\dist\ThreatDetector.jar"
exit /b 0
