# QBank — Portable Question Bank, Paper Generation & Exam Session System

A security-hardened, offline-LAN **Question Bank, Paper Generation, and Exam Delivery Foundation** designed in a hexagonal multi-module Maven architecture for Java 17 and Spring Boot 3. Supports standalone LAN deployment as well as drop-in embedding into an existing institutional exam portal.

---

## Architecture Overview

| Module | Purpose | Technical Stack / Guarantees |
|---|---|---|
| [`qbank-core`](qbank-core) | Pure domain models, validation, auto-shuffle, 2-session paper generator, deterministic scorer, services, and hexagonal ports | Pure Java 17 (enforced by ArchUnit `CoreArchitectureTest` — zero Spring, JDBC, web, filesystem, or Apache POI imports) |
| [`qbank-persistence-jdbc`](qbank-persistence-jdbc) | Plain JDBC repositories, isolated Flyway migrations (`qbank_*` tables, `qbank_schema_history`), AES-256-GCM encryption, hardened Excel POI parser/sanitizer, `.qbank.enc` backup/restore | Supports H2, PostgreSQL, SQLite, MySQL; zero Hibernate/JPA requirement |
| [`qbank-ui`](qbank-ui) | Plain HTML/CSS/JS offline-LAN assets + embeddable `QBankStudentView` component (`META-INF/resources/qbank/`) | Zero external CDNs, fonts, or telemetry; font-size & high-contrast controls; offline `localStorage` queue & auto-resync |
| [`qbank-spring-boot-starter`](qbank-spring-boot-starter) | Spring Boot 3 auto-configuration (`QBankAutoConfiguration`), configurable subpath (`/qbank`), REST `/api/v1/**`, `HostSecurityBridge`, domain events, scheduler, health monitor | Works in **standalone** (BCrypt users + setup wizard) or **host** (Spring Security delegation) mode |
| [`qbank-standalone`](qbank-standalone) | Thin runnable JAR packaging all modules for standalone LAN execution & offline encrypted restore CLI | Startup preflight, key/TLS/restore scripts (`.bat` & `.sh`), PostgreSQL streaming HA templates |
| [`examples/host-app`](examples/host-app) | Reference host Spring Boot 3 application consuming `qbank-spring-boot-starter` cleanly | Demonstrates shared Flyway schema coexistence, host Spring Security roles, `PaperFrozenEvent`, and `FrozenPaperProvider` |

Additional Documentation:
- Integration Guide: [`INTEGRATION.md`](INTEGRATION.md)
- OpenAPI Specification: [`docs/openapi-qbank-v1.yaml`](docs/openapi-qbank-v1.yaml)
- Operations & Recovery Runbook: [`RUNBOOK.md`](RUNBOOK.md)

---

## Key Security & Exam Integrity Guarantees

1. **Encryption at Rest & Confidentiality**:
   - Bank MCQs and frozen 4-version paper snapshots (`A`–`D`) are encrypted with **AES-256-GCM** using external 256-bit keys loaded from env vars or key files stored outside the database/storage directories.
   - SHA-256 ciphertext digests are verified before decrypting any frozen paper (`DefaultFrozenPaperProvider`).
2. **Confidential Workflows**:
   - **HOD Import**: Preview shows rows in Excel order only to resolve errors/warnings. Confirmation immediately auto-shuffles question order and option order with `SecureRandom`, remaps `correctIndex`, deletes draft preview data, and returns counts only.
   - **Blind COE Selection**: Paper generation uses 8 disjoint chunks per module across `MORNING` and `AFTERNOON` sessions (or reserves capacity cleanly if afternoon is generated later), blocking if `8 * perModule > module.questionCount`. COE sees only redacted `SetSummaryCard` summaries (`setNumber`, `questionCount`, `status`) — never stems, options, MCQ IDs, or answer keys.
   - **Time-Window Enforcement**: Server-authoritative clock (`ClockPort`) rejects frozen paper decryption and student paper retrieval before `scheduledStartAt` or after `scheduledEndAt + grantedExtraMinutes`. Student APIs and UI never receive answer keys.
3. **Hardened Excel Handling**:
   - Enforces file size limits, OOXML magic-byte signature validation (not just extension checks), Apache POI XML entity protections and zip-bomb ratio limits (`HardenedPoiSecurityTest`), plain-text cell normalization, output escaping, and formula injection neutralization (`=`, `+`, `-`, `@` prefixed cleanly) via `FormulaSanitizer` on exports.
4. **Tamper-Evident Hash-Chained Audit Log**:
   - `qbank_audit_log` chains every entry via SHA-256 of the preceding entry's hash. Admins can verify end-to-end chain integrity via `POST /api/v1/admin/audit/verify`.
5. **High-Stakes Session Control & Reliability**:
   - One active login per student (`activeTokenJti`) enforced server-side via HMAC-SHA256 `qbs1.` tokens; Invigilator room monitors can track progress, grant per-student or room-wide extra time, and reset logins for workstation failover.
   - Deterministic scoring explicitly tracks `unansweredCount`, distinguishing unanswered items from wrong answers (`DeterministicScorerTest`).
   - Synchronous commit durability (`WRITE_DELAY=0` configured automatically for H2; `PRAGMA synchronous=FULL` WAL for SQLite; `synchronous_commit = remote_apply` template for PostgreSQL) prevents loss of acknowledged student answers on abrupt server crashes (`MidExamCrashRecoveryIntegrationTest`).

---

## Quick Start

### 1. Build, Test, and Install All Modules

```bash
mvn -B install
```

### 2. Run Standalone LAN Server

See [`RUNBOOK.md`](RUNBOOK.md) for full production TLS, external key generation, backup, and failover steps. Quick local HTTP/HTTPS startup:

```bash
cd qbank-standalone
# Windows:
scripts\generate-keys.bat
start.bat

# Linux / macOS:
./scripts/generate-keys.sh
./start.sh
```

Access the control portal at `http://127.0.0.1:8443/qbank/` (or `https://` once `QBANK_SSL_ENABLED=true` and `scripts/generate-tls-cert` have been run) and candidate login at `/qbank/student.html`.

### 3. Embed in an Existing Spring Boot 3 Host App

Add `qbank-spring-boot-starter:1.0.0` (never `qbank-standalone`) to your host POM, map host Spring Security authorities in `application.properties`, and consume `FrozenPaperProvider` and `PaperFrozenEvent` cleanly. See [`INTEGRATION.md`](INTEGRATION.md) and [`examples/host-app`](examples/host-app) for the complete reference setup.

---

## Verified Scope Summary

| Suite / Check | Scope Covered |
|---|---|
| `qbank-core` (6 test classes) | ArchUnit purity boundaries, auto-shuffle determinism & preview deletion, 2-session 8-chunk disjointness & `8 * perModule` capacity guard, deterministic scorer (positive/negative/unanswered), student login window/single-session/`extraTime`/audit-hash security |
| `qbank-persistence-jdbc` (4 test classes) | JDBC contracts, AES-GCM ciphertext opacity at rest, isolated `qbank_*` / `qbank_schema_history` migrations, Apache POI XXE/zip-bomb/magic-byte/formula sanitization, encrypted `.qbank.enc` backup & atomic restore, SHA-256 audit chain tamper detection |
| `qbank-spring-boot-starter` (2 test classes) | Full standalone HTTP E2E flow (`setup-admin` -> multi-module upload -> dual session -> blind COE approve -> Invigilator monitor & extra time -> student exam & CSV/XLSX export); host-mode Spring Security delegation (`HostSecurityBridge`, room scoping, token gating) |
| `qbank-standalone` (4 test classes) | Preflight key-separation & TLS validation, forcible JVM `destroyForcibly()` crash recovery with `< 2 min` RTO and 0 lost answers, multi-JVM offline encrypted snapshot restore failover, 120-student concurrent load simulation |
| `examples/host-app` (1 integration test class) | Host Flyway (`flyway_schema_history`) + QBank Flyway (`qbank_schema_history`) coexistence, `HostSecurityConfig` authority mapping, `PaperFrozenEvent`, window-gated `FrozenPaperProvider` |
| External Isolated Acceptance | Executed `generate-keys.bat` + `generate-tls-cert.bat` (`icacls` user-only `:F` ACL), booted `qbank-standalone-1.0.0.jar` over HTTPS outside the repo with CSP/`X-Frame-Options`/`nosniff`, and built/tested a detached copy of `examples/host-app` against `~/.m2` |

See [`RUNBOOK.md#8-pre-exam-verification--known-limitations-checklist`](RUNBOOK.md#8-pre-exam-verification--known-limitations-checklist) for environment constraints observed during verification.
