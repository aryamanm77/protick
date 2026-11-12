#!/usr/bin/env bash
# Promotes the Hot Standby PostgreSQL node to Primary and starts the standby QBank server.
# Target RTO: < 2 minutes end-to-end.

set -euo pipefail

PG_DATA="${PG_DATA:?Set PG_DATA path on standby host}"
QBANK_SERVICE="${QBANK_SERVICE:-qbank-standalone}"
VIP_SWITCH_CMD="${VIP_SWITCH_CMD:-}"

echo "[1/4] Verifying primary health before promoting standby..."
if [[ -n "${PRIMARY_HOST:-}" ]] && pg_isready -h "${PRIMARY_HOST}" -p "${PRIMARY_PORT:-5432}" -t 3 >/dev/null 2>&1; then
  echo "ABORT: Primary ${PRIMARY_HOST} still appears reachable! Stop primary first to avoid split-brain." >&2
  exit 1
fi

echo "[2/4] Promoting PostgreSQL standby at ${PG_DATA}..."
sudo -u postgres pg_ctl -D "${PG_DATA}" promote -t 30
sleep 2
psql -U postgres -d qbank -tAc "SELECT pg_is_in_recovery();" | grep -q "f"

echo "[3/4] Updating virtual IP / DNS (if configured) and starting standby QBank instance..."
if [[ -n "${VIP_SWITCH_CMD}" ]]; then
  eval "${VIP_SWITCH_CMD}"
fi
systemctl restart "${QBANK_SERVICE}"

echo "[4/4] Waiting for QBank standby HTTP health..."
for _ in $(seq 1 30); do
  if curl -kfsS "https://127.0.0.1:${QBANK_PORT:-8443}${QBANK_BASE_PATH:-/qbank}/api/v1/auth/info" >/dev/null 2>&1; then
    echo "FAILOVER COMPLETE: Standby QBank is serving requests cleanly."
    exit 0
  fi
  sleep 2
done

echo "WARNING: Standby QBank did not respond within 60s; inspect journalctl -u ${QBANK_SERVICE}." >&2
exit 1
