@echo off
setlocal
set ROOT_DIR=%~dp0
pushd "%ROOT_DIR%"

if not exist "secrets\qbank-aes.key" (
  echo Missing secrets\qbank-aes.key. Run scripts\generate-keys.bat first.
  popd
  exit /b 1
)

set JAR_FILE=
for %%F in (target\qbank-standalone-*.jar qbank-standalone-*.jar) do (
  if exist "%%F" set JAR_FILE=%%F
)
if "%JAR_FILE%"=="" (
  echo Executable JAR not found. Run: mvn -B package
  popd
  exit /b 1
)

java -jar "%JAR_FILE%" --spring.config.additional-location=optional:file:./config/application.properties %*
set RC=%ERRORLEVEL%
popd
exit /b %RC%
