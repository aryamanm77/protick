#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CERTS_DIR="${ROOT_DIR}/certs"
SAN_IP="${1:-127.0.0.1}"

if [[ -z "${QBANK_TLS_KEYSTORE_PASSWORD:-}" ]]; then
  echo "ERROR: Export QBANK_TLS_KEYSTORE_PASSWORD before running generate-tls-cert.sh" >&2
  exit 1
fi

mkdir -p "${CERTS_DIR}"
chmod 700 "${CERTS_DIR}"
KEYSTORE="${CERTS_DIR}/qbank-keystore.p12"
rm -f "${KEYSTORE}"

KEYTOOL_BIN="keytool"
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/keytool" ]]; then
  KEYTOOL_BIN="${JAVA_HOME}/bin/keytool"
fi

"${KEYTOOL_BIN}" -genkeypair \
  -alias qbank-lan \
  -keyalg RSA \
  -keysize 2048 \
  -sigalg SHA256withRSA \
  -validity 825 \
  -storetype PKCS12 \
  -keystore "${KEYSTORE}" \
  -storepass:env QBANK_TLS_KEYSTORE_PASSWORD \
  -dname "CN=qbank-lan, OU=Examination Cell, O=Institution, C=IN" \
  -ext "SAN=IP:${SAN_IP},IP:127.0.0.1,DNS:localhost"

chmod 600 "${KEYSTORE}"
echo "Generated TLS keystore at ${KEYSTORE}"
