@echo off
rem Build and run the JavaFX app from source (development loop).
setlocal
cd /d "%~dp0"

call mvn -q -DskipTests package
if errorlevel 1 (
  echo Build failed.
  exit /b 1
)

java -jar "app\target\threat-detector-java-1.0.0-all.jar" %*
exit /b %errorlevel%
