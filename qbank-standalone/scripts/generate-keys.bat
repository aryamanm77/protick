@echo off
setlocal enabledelayedexpansion
set SCRIPT_DIR=%~dp0
set ROOT_DIR=%SCRIPT_DIR%..
set SECRETS_DIR=%ROOT_DIR%\secrets

if not exist "%SECRETS_DIR%" mkdir "%SECRETS_DIR%"

powershell -NoProfile -Command ^
  "$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create();" ^
  "$b1 = New-Object byte[] 32; $rng.GetBytes($b1);" ^
  "$b2 = New-Object byte[] 32; $rng.GetBytes($b2);" ^
  "[System.IO.File]::WriteAllText('%SECRETS_DIR%\qbank-aes.key', [Convert]::ToBase64String($b1));" ^
  "[System.IO.File]::WriteAllText('%SECRETS_DIR%\qbank-hmac.key', [Convert]::ToBase64String($b2));"

if errorlevel 1 (
  echo Failed to generate cryptographic keys.
  exit /b 1
)

icacls "%SECRETS_DIR%\qbank-aes.key" /inheritance:r /grant:r "%USERNAME%:F" >nul 2>&1
icacls "%SECRETS_DIR%\qbank-hmac.key" /inheritance:r /grant:r "%USERNAME%:F" >nul 2>&1

echo Generated 256-bit keys outside data directory:
echo   %SECRETS_DIR%\qbank-aes.key
echo   %SECRETS_DIR%\qbank-hmac.key
