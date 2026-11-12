#!/usr/bin/env bash
# Initializes a PostgreSQL Hot Standby node streaming from the Primary exam DB.
# Example:
#   PRIMARY_HOST=10.10.0.10 PG_DATA=/var/lib/postgresql/16/main REPLICATOR_PASSWORD='...' ./setup-standby.sh

set -euo pipefail

PRIMARY_HOST="${PRIMARY_HOST:?Set PRIMARY_HOST IP/hostname}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
PG_DATA="${PG_DATA:?Set PG_DATA path on standby host}"
REPLICATOR_USER="${REPLICATOR_USER:-replicator}"
REPLICATOR_PASSWORD="${REPLICATOR_PASSWORD:?Set REPLICATOR_PASSWORD}"
SLOT_NAME="${SLOT_NAME:-qbank_standby_1}"

systemctl stop postgresql || true
rm -rf "${PG_DATA:?}"/*

PGPASSWORD="${REPLICATOR_PASSWORD}" pg_basebackup \
  -h "${PRIMARY_HOST}" \
  -p "${PRIMARY_PORT}" \
  -U "${REPLICATOR_USER}" \
  -D "${PG_DATA}" \
  -Fp -Xs -P -R \
  -C -S "${SLOT_NAME}"

cat >> "${PG_DATA}/postgresql.auto.conf" <<EOF
primary_conninfo = 'host=${PRIMARY_HOST} port=${PRIMARY_PORT} user=${REPLICATOR_USER} password=${REPLICATOR_PASSWORD} application_name=${SLOT_NAME}'
hot_standby = on
EOF

chown -R postgres:postgres "${PG_DATA}"
chmod 700 "${PG_DATA}"
systemctl start postgresql

echo "Standby initialized and streaming from ${PRIMARY_HOST}:${PRIMARY_PORT} using slot ${SLOT_NAME}."
