@echo off
rem Full verification: unit + parity tests, then smoke and WebView compatibility.
setlocal
cd /d "%~dp0"

call mvn -q clean verify
if errorlevel 1 (
  echo CHECKS FAILED: mvn verify
  exit /b 1
)

java -jar "app\target\threat-detector-java-1.0.0-all.jar" --smoke-test
if errorlevel 1 (
  echo CHECKS FAILED: smoke test
  exit /b 1
)

java -jar "app\target\threat-detector-java-1.0.0-all.jar" --compat-audit
if errorlevel 1 (
  echo CHECKS FAILED: compatibility audit
  exit /b 1
)

echo CHECKS PASSED
exit /b 0
