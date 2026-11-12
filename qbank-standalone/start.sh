#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${ROOT_DIR}"

if [[ ! -f "secrets/qbank-aes.key" ]]; then
  echo "Missing secrets/qbank-aes.key. Run ./scripts/generate-keys.sh first." >&2
  exit 1
fi

JAR_FILE=""
for f in target/qbank-standalone-*.jar qbank-standalone-*.jar; do
  if [[ -f "$f" ]]; then
    JAR_FILE="$f"
    break
  fi
done

if [[ -z "${JAR_FILE}" ]]; then
  echo "Executable JAR not found. Run: mvn -B package" >&2
  exit 1
fi

exec java -jar "${JAR_FILE}" --spring.config.additional-location=optional:file:./config/application.properties "$@"
