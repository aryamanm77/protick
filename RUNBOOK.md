# QBank Operational Runbook

Operational procedures for deploying, securing, backing up, and recovering the **QBank Question Bank & Exam Session Server** on an isolated offline LAN.

---

## 1. Prerequisites & Host Environment

- **Java**: JRE / JDK 17+ (`JAVA_HOME` configured; `keytool` required when generating LAN TLS certificates).
- **Artifacts**:
  - Built executable server JAR: `qbank-standalone/target/qbank-standalone-1.0.0.jar` (built via `mvn -B package` or `mvn -B install`).
  - Configuration & scripts directory: `qbank-standalone/config/`, `qbank-standalone/scripts/`, `qbank-standalone/deploy/postgresql/`.
- **High-Availability Database (recommended for high-stakes exams)**: PostgreSQL 15/16+ primary + hot standby node. For single-node LAN testing/smaller cohorts, embedded H2 file mode (`WRITE_DELAY=0`) or SQLite WAL mode (`PRAGMA synchronous=FULL`) is supported.

---

## 2. First-Time Key, Certificate & Directory Setup

> [!CAUTION]
> Cryptographic keys (`secrets/`) and database/backup storage (`data/`) **must** live in separate directories. `StartupPreflight` refuses to boot if any key file is placed inside `qbank.storage-dir`.

### 2.1 Generate External 256-bit Encryption & Signing Keys

Creates 32-byte Base64 AES-256-GCM and HMAC-SHA256 keys under `qbank-standalone/secrets/` with restricted file ACLs (`600` on POSIX, user-only full control on Windows):

- **Windows**:
  ```bat
  cd qbank-standalone
  scripts\generate-keys.bat
  ```
- **Linux/macOS**:
  ```bash
  cd qbank-standalone
  ./scripts/generate-keys.sh
  ```

Outputs:
- `secrets/qbank-aes.key` (`qbank.security.encryption-key-file`)
- `secrets/qbank-hmac.key` (`qbank.security.signing-key-file`)

### 2.2 Generate Self-Signed LAN TLS Keystore (PKCS12)

Set a strong keystore password in `QBANK_TLS_KEYSTORE_PASSWORD` and supply the server's LAN IP so the certificate Subject Alternative Name (SAN) matches client requests:

- **Windows (cmd)**:
  ```bat
  set QBANK_TLS_KEYSTORE_PASSWORD=<strong-tls-password>
  scripts\generate-tls-cert.bat 10.10.0.10
  ```
- **Linux/macOS**:
  ```bash
  export QBANK_TLS_KEYSTORE_PASSWORD='<strong-tls-password>'
  ./scripts/generate-tls-cert.sh 10.10.0.10
  ```

Outputs:
- `certs/qbank-keystore.p12` (alias `qbank-lan`, RSA 2048-bit, `SHA256withRSA`, valid 825 days, SAN `IP:<LAN_IP>,IP:127.0.0.1,DNS:localhost`).

To use institutional CA certificates instead, configure `server.ssl.key-store`, `server.ssl.key-store-type`, and `QBANK_TLS_KEYSTORE_PASSWORD` in `qbank-standalone/config/application.properties`.

---

## 3. Configuration & Launching the Standalone Server

Edit `qbank-standalone/config/application.properties` before launch:

| Property / Env Var | Purpose | Default / Example |
|---|---|---|
| `server.port` | HTTP/HTTPS listener port | `8443` |
| `server.address` | Bind specific NIC / LAN IP (do not expose beyond exam VLAN) | `0.0.0.0` (template) / `127.0.0.1` (fallback) |
| `QBANK_SSL_ENABLED` | Enables TLS when keystore is present | `false` (`true` for LAN HTTPS) |
| `QBANK_TLS_KEYSTORE_PASSWORD` | Password for `./certs/qbank-keystore.p12` | Required when TLS enabled |
| `qbank.storage-dir` | Directory for `.qbank.enc` encrypted backups | `./data/qbank-storage` |
| `qbank.datasource.url` | H2, PostgreSQL, SQLite, or MySQL JDBC URL | `jdbc:h2:file:./data/qbank-db;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0` |
| `QBANK_DB_PASSWORD` | Database password env var | Required for PostgreSQL/MySQL |
| `QBANK_ENGINE_TOKEN` | Machine header `X-QBank-Engine-Token` for `/api/v1/engine/**` | Optional if external runner pulls frozen papers |

Launch:
- **Windows**:
  ```bat
  set QBANK_SSL_ENABLED=true
  set QBANK_TLS_KEYSTORE_PASSWORD=<strong-tls-password>
  start.bat
  ```
- **Linux/macOS**:
  ```bash
  export QBANK_SSL_ENABLED=true
  export QBANK_TLS_KEYSTORE_PASSWORD='<strong-tls-password>'
  ./start.sh
  ```

### First-Run Setup Wizard
1. Open `https://<LAN_IP>:8443/qbank/` in a LAN browser.
2. When no `ADMIN` exists in `qbank_users`, `/api/v1/auth/info` returns `setupRequired: true` and prompts for initial `ADMIN` creation (`POST /api/v1/auth/setup-admin`). Once an `ADMIN` exists, setup is permanently disabled (`409 Conflict`).
3. Log in as `ADMIN` and provision `HOD`, `COE`, and `INVIGILATOR` accounts (`POST /api/v1/admin/users`) with temporary passwords; all newly created accounts are forced to change their password on first login.

---

## 4. Encrypted Backups & Offline / Online Restore

All backups serialize every `qbank_*` table inside a single read transaction, compute a payload SHA-256 digest, encrypt with AES-256-GCM (`AAD=qbank-backup-v1`), and write timestamped `.qbank.enc` files under `${qbank.storage-dir}/backups/`. Retention keeps the newest `qbank.backup.keep-last` artifacts (30 in `config/application.properties`, 14 in default fallback).

### 4.1 Automatic Backups (`QBankBackupScheduler`)
- **Daily backup**: cron `${qbank.backup.daily-cron:0 0 2 * * *}` (tag `daily`); also prunes expired draft import previews older than 24 hours.
- **Live exam snapshots**: every `${qbank.backup.live-session-interval-minutes:5}` minutes while any session is in status `LIVE` (tag `live`).

### 4.2 Manual Backup & Inspect (ADMIN)
- Create manual snapshot: `POST /qbank/api/v1/admin/backups?tag=manual` (or click **Create Encrypted Backup** in Admin Console).
- List & download: `GET /qbank/api/v1/admin/backups` -> `GET /qbank/api/v1/admin/backups/{filename}`.
- Preview table counts & digest before restore: `POST /qbank/api/v1/admin/backups/inspect` (multipart `.qbank.enc` file).
- Online restore (maintenance window): `POST /qbank/api/v1/admin/backups/restore?confirm=true`.

### 4.3 Offline CLI Restore (Target DB Stopped / Disaster Recovery)
When restoring onto a replacement node or after DB corruption, keep the same `secrets/qbank-aes.key` and `secrets/qbank-hmac.key`, stop any running server process against the target DB, and run:

- **Windows**:
  ```bat
  cd qbank-standalone
  scripts\restore-backup.bat path\to\qbank-backup-live-YYYYMMDD-HHmmss.qbank.enc
  ```
- **Linux/macOS**:
  ```bash
  cd qbank-standalone
  ./scripts/restore-backup.sh path/to/qbank-backup-live-YYYYMMDD-HHmmss.qbank.enc
  ```

The script prompts for explicit `YES` confirmation and runs `QBankStandaloneApplication` with `--qbank.restore-file=... --qbank.restore-confirm=true` (`WebApplicationType.NONE`), atomically replacing all `qbank_*` tables, logging `OFFLINE_CLI` restore audit records, and executing `AuditPort.verifyChain()` before exiting with code `0`.

---

## 5. High-Availability PostgreSQL Streaming Replication & Failover

Templates under `qbank-standalone/deploy/postgresql/`:

1. **Primary Setup** (`postgresql-primary.conf`, `pg_hba.conf`):
   - Configure `wal_level = replica`, `hot_standby = on`, `synchronous_commit = remote_apply`, and `synchronous_standby_names = 'qbank_standby_1'` for zero-loss acknowledged commits.
   - Restrict TLS LAN CIDR rules in `pg_hba.conf` (`hostssl qbank qbank_app ... scram-sha-256` and `hostssl replication replicator ...`).
   - Point `qbank.datasource.url` to the primary PostgreSQL database (`jdbc:postgresql://<primary-ip>:5432/qbank`).

2. **Initialize Hot Standby**:
   ```bash
   PRIMARY_HOST=10.10.0.10 PG_DATA=/var/lib/postgresql/16/main REPLICATOR_PASSWORD='<pass>' \
     ./qbank-standalone/deploy/postgresql/setup-standby.sh
   ```
   Copy `secrets/qbank-aes.key`, `secrets/qbank-hmac.key`, and `certs/qbank-keystore.p12` securely to the standby QBank node and verify QBank service remains stopped while standby PostgreSQL is in recovery.

3. **Promote Standby on Primary Failure** (target RTO `< 2 minutes`):
   ```bash
   PRIMARY_HOST=10.10.0.10 PG_DATA=/var/lib/postgresql/16/main \
     ./qbank-standalone/deploy/postgresql/failover.sh
   ```
   `failover.sh` aborts if the primary is still reachable (preventing split-brain), promotes PostgreSQL (`pg_ctl promote`), asserts `pg_is_in_recovery() = f`, runs an optional `VIP_SWITCH_CMD`, restarts `qbank-standalone`, and polls `/api/v1/auth/info` until healthy.

---

## 6. Mid-Exam Incident Playbooks (< 2-Minute Recovery)

### Scenario A: Student Workstation Crashes / Loses Power / Network Drop
1. While disconnected, the student browser UI retains queued selections in `localStorage` and retries resync cleanly on reconnect.
2. If moving the student to a spare workstation, have the hall **INVIGILATOR** open **Room Monitor** (`GET /qbank/api/v1/invigilator/sessions/{sessionId}/monitor?roomCode=...`) and click **Reset Login** (`POST .../students/{rollNumber}/reset-login`) to clear the active `jti` session lock.
3. On the spare PC, the student signs in with the same roll number and one-time code, resumes at the recorded `lastQuestionIndex`, and receives all previously acknowledged `savedSelections`.
4. The Invigilator may grant per-student or whole-room extra minutes (`POST .../extra-time`); every grant is written to `qbank_extra_time_grants` and hash-chained in `qbank_audit_log`.

### Scenario B: QBank Application Server Process Crashes Mid-Exam
1. Ensure old process is terminated so DB file locks (H2/SQLite) or port `8443` are released.
2. Start the server again (`start.bat` / `start.sh`) with the exact same `secrets/` keys and `data/` DB (H2 `WRITE_DELAY=0` commits synchronously on every response).
3. Students already holding valid signed `qbs1.` tokens continue auto-saving; students whose browsers reload or move to another machine can have their session reset by the Invigilator cleanly.

### Scenario C: Corrupt Primary Host Requires Switching to Cold/Warm Backup Node
1. Provision standby node with identical `secrets/` keys (required to decrypt frozen papers and `.qbank.enc` backups).
2. Restore the most recent live snapshot via `scripts/restore-backup.sh` (or `.bat`), verify exit code `0`, and start `start.sh` / `start.bat`.
3. Verify audit hash chain integrity via `POST /qbank/api/v1/admin/audit/verify` and `GET /qbank/api/v1/admin/health`.

---

## 7. Troubleshooting & Rollback

- **`Missing readable key file ... Run scripts/generate-keys`**: Check `qbank.security.encryption-key-file` / `signing-key-file` paths relative to working directory (or supply `QBANK_ENCRYPTION_KEY` / `QBANK_SIGNING_KEY` base64 env vars).
- **`Security violation: ... must reside outside the storage/data directory`**: Move `secrets/` outside `qbank.storage-dir`.
- **`HTTPS is enabled ... keystore was not found`**: Run `scripts/generate-tls-cert`, or set `QBANK_SSL_ENABLED=false` strictly behind a TLS-terminating reverse proxy.
- **`Found non-empty schema(s)` in embedded host app**: Ensure you consume `qbank-spring-boot-starter:1.0.0` (which runs `qbankFlywayOrderingPostProcessor` so host `flywayInitializer` migrates first and QBank tracks isolated history in `qbank_schema_history`).
- **Port `8443` already in use**: Override `--server.port=<port>` or check stale JVM PIDs.
- **Database H2 file lock timeout**: Confirm no concurrent JVM process holds `data/qbank-db.mv.db` before running `restore-backup` or restarting.
- **Audit integrity alert (`AuditVerificationResult.intact == false`)**: `firstBrokenSeqNo` pinpoints the first broken link in `qbank_audit_log`. Preserve the database copy for forensic inspection and restore clean state only after verification.
- **Upgrade Rollback**: Keep a manual pre-upgrade `.qbank.enc` backup, stop the server, restore the previous JAR + backup via `scripts/restore-backup`, and start the prior version.

---

## 8. Pre-Exam Verification & Known Limitations Checklist

### Operator Pre-Exam Checklist
1. [ ] Verify `GET /qbank/api/v1/admin/health` reports `dbStatus=UP` and `POST /qbank/api/v1/admin/audit/verify` returns `intact=true`.
2. [ ] Verify scheduled daily & live backup folder (`${qbank.storage-dir}/backups/`) is writable and recent `.qbank.enc` files exist.
3. [ ] Verify LAN firewall permits HTTPS port (`8443`) only from exam halls / staff VLANs and blocks external egress.
4. [ ] Test keyboard navigation (`Tab` / `Shift+Tab`), font scaling, and high-contrast toggle cleanly on `student.html` with your target browser/screen reader before exam day.
5. [ ] Execute a timed dry run of `failover.sh` (or offline `.qbank.enc` restore to standby) on staging hardware matching production PostgreSQL.

### Documented Build-Host Verification Constraints
- **PostgreSQL HA & Failover Scripts**: `postgresql-primary.conf`, `pg_hba.conf`, `setup-standby.sh`, and `failover.sh` were reviewed against PostgreSQL 15/16 tooling flags, but **not live-executed** on this Windows build host because `psql`, `pg_ctl`, and `docker` were not present. Failover verification on this host used `MultiProcessStandbyFailoverTest` (encrypting a live DB snapshot and restoring it onto a second JVM via offline CLI).
- **OWASP Dependency-Check**: `mvn -B org.owasp:dependency-check-maven:11.1.0:check -DfailBuildOnCVSS=11` reached 57% NVD download (`240,000/424,170` records) before termination due to unauthenticated NVD API rate-limiting (~40+ min without an API key). Run the full scan in CI with `-DnvdApiKey=<key>`.
- **Browser Visual Walk**: Live Playwright browser inspection verified initial rendering, `setup-admin`, login, and Admin UI shell; all multi-role API permissions, paper blindness, crash recovery, and 120-student load flows were verified automatically through JUnit 5, MockMvc, and multi-JVM integration suites.
