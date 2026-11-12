@echo off
setlocal
if "%~1"=="" (
  echo Usage: restore-backup.bat ^<path-to-backup.qbank.enc^>
  exit /b 1
)
set BACKUP_FILE=%~1
set SCRIPT_DIR=%~dp0
set ROOT_DIR=%SCRIPT_DIR%..
pushd "%ROOT_DIR%"
set JAR_FILE=
for %%F in (target\qbank-standalone-*.jar qbank-standalone-*.jar) do (
  if exist "%%F" set JAR_FILE=%%F
)
if "%JAR_FILE%"=="" (
  echo ERROR: qbank-standalone JAR not found. Build with mvn package first.
  popd
  exit /b 1
)
echo WARNING: Restoring will REPLACE all QBank database tables cleanly in a single transaction.
set /p CONF="Type YES to confirm offline restore from %BACKUP_FILE%: "
if not "%CONF%"=="YES" (
  echo Restore aborted by operator.
  popd
  exit /b 1
)
java -jar "%JAR_FILE%" --spring.config.additional-location=optional:file:./config/application.properties --qbank.restore-file="%BACKUP_FILE%" --qbank.restore-confirm=true
set RC=%ERRORLEVEL%
popd
exit /b %RC%
