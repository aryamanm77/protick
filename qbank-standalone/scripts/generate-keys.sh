#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SECRETS_DIR="${1:-${ROOT_DIR}/secrets}"

mkdir -p "${SECRETS_DIR}"
chmod 700 "${SECRETS_DIR}"

generate_b64_32() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import os,base64; print(base64.b64encode(os.urandom(32)).decode())'
  else
    head -c 32 /dev/urandom | base64 | tr -d '\n'
  fi
}

generate_b64_32 > "${SECRETS_DIR}/qbank-aes.key"
generate_b64_32 > "${SECRETS_DIR}/qbank-hmac.key"
chmod 600 "${SECRETS_DIR}/qbank-aes.key" "${SECRETS_DIR}/qbank-hmac.key"

echo "Generated 256-bit keys in ${SECRETS_DIR}:"
echo "  ${SECRETS_DIR}/qbank-aes.key"
echo "  ${SECRETS_DIR}/qbank-hmac.key"
