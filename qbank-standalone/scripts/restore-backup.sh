#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <path-to-backup.qbank.enc>" >&2
  exit 1
fi

BACKUP_FILE="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

JAR_FILE=""
for f in target/qbank-standalone-*.jar qbank-standalone-*.jar; do
  if [[ -f "$f" ]]; then
    JAR_FILE="$f"
    break
  fi
done

if [[ -z "${JAR_FILE}" ]]; then
  echo "ERROR: qbank-standalone JAR not found." >&2
  exit 1
fi

echo "WARNING: Restoring will atomically replace all qbank_* tables from ${BACKUP_FILE}."
read -r -p "Type YES to confirm restore: " CONF
if [[ "${CONF}" != "YES" ]]; then
  echo "Restore aborted."
  exit 1
fi

exec java -jar "${JAR_FILE}" \
  --spring.config.additional-location=optional:file:./config/application.properties \
  --qbank.restore-file="${BACKUP_FILE}" \
  --qbank.restore-confirm=true
