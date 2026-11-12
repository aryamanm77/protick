@echo off
setlocal enabledelayedexpansion
set SCRIPT_DIR=%~dp0
set ROOT_DIR=%SCRIPT_DIR%..
set CERTS_DIR=%ROOT_DIR%\certs

set SAN_IP=%1
if "%SAN_IP%"=="" set SAN_IP=127.0.0.1

if "%QBANK_TLS_KEYSTORE_PASSWORD%"=="" (
  echo Set QBANK_TLS_KEYSTORE_PASSWORD environment variable before generating the TLS keystore.
  exit /b 1
)

if not exist "%CERTS_DIR%" mkdir "%CERTS_DIR%"
set KEYSTORE=%CERTS_DIR%\qbank-keystore.p12
if exist "%KEYSTORE%" del /f /q "%KEYSTORE%"

set KEYTOOL_CMD=keytool
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set KEYTOOL_CMD=%JAVA_HOME%\bin\keytool.exe

"%KEYTOOL_CMD%" -genkeypair ^
  -alias qbank-lan ^
  -keyalg RSA ^
  -keysize 2048 ^
  -sigalg SHA256withRSA ^
  -validity 825 ^
  -storetype PKCS12 ^
  -keystore "%KEYSTORE%" ^
  -storepass:env QBANK_TLS_KEYSTORE_PASSWORD ^
  -dname "CN=qbank-lan, OU=Examination Cell, O=Institution, C=IN" ^
  -ext "SAN=IP:%SAN_IP%,IP:127.0.0.1,DNS:localhost"

if errorlevel 1 (
  echo Failed to generate TLS keystore. Ensure JDK keytool is installed.
  exit /b 1
)

icacls "%KEYSTORE%" /inheritance:r /grant:r "%USERNAME%:F" >nul 2>&1
echo Generated TLS keystore at: %KEYSTORE%
