# Integrating QBank into an Existing Host Exam Application

`qbank-spring-boot-starter` is built on a pure hexagonal core (`qbank-core`) and an isolated plain-JDBC + Flyway persistence module (`qbank-persistence-jdbc`). It drops into any Spring Boot 3 / Java 17 host application with minimal wiring and zero table-name or Flyway-history collisions.

---

## 1. Add Maven Dependency

First build and install the reactor (or publish to your internal LAN Maven repository):

```bash
mvn -B install
```

Then add `qbank-spring-boot-starter` to the host application `pom.xml`:

```xml
<dependency>
    <groupId>com.yourorg.qbank</groupId>
    <artifactId>qbank-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

> Do **not** add `qbank-standalone` as a dependency of your host application.

---

## 2. Configuration Properties

Minimal `application.properties` settings in a host application:

```properties
# Enable or toggle the entire module
qbank.enabled=true

# Mount static UI and REST endpoints cleanly under any subpath (default /qbank)
qbank.base-path=/exam-bank

# Auth mode: auto (default - delegates to host when Spring Security is present), host, or standalone
qbank.auth.mode=auto

# Externalized 256-bit base64 keys or key files outside data/storage directories
qbank.security.encryption-key-base64=${QBANK_ENCRYPTION_KEY:}
qbank.security.signing-key-base64=${QBANK_SIGNING_KEY:}

# Map your host app authorities directly to QBank roles (comma-separated)
qbank.roles.admin=PORTAL_ADMIN,ROLE_ADMIN
qbank.roles.hod=DEPT_CHAIR,ROLE_HOD
qbank.roles.coe=EXAM_CTRL,ROLE_COE
qbank.roles.invigilator=HALL_PROCTOR,ROLE_INVIGILATOR
qbank.roles.room-authority-prefix=EXAM_ROOM:

# Optional dedicated DataSource (leave blank to reuse the host primary DataSource)
# qbank.datasource.url=jdbc:postgresql://localhost:5432/qbank
# qbank.datasource.username=qbank
# qbank.datasource.password=${QBANK_DB_PASSWORD:}

# Storage path for encrypted backups (.qbank.enc)
qbank.storage-dir=./data/qbank-storage
```

---

## 3. Database & Flyway Isolation

- **All tables are prefixed with `qbank_`**: `qbank_subjects`, `qbank_modules`, `qbank_mcqs`, `qbank_import_previews`, `qbank_exams`, `qbank_exam_sessions`, `qbank_paper_sets`, `qbank_paper_versions`, `qbank_exam_students`, `qbank_student_responses`, `qbank_extra_time_grants`, `qbank_users`, `qbank_user_sessions`, `qbank_audit_log`.
- **Isolated Flyway migrations**: Dialect migrations (`h2`, `postgresql`, `sqlite`, `mysql`) reside at `classpath:qbank/db/migration/<dialect>` (outside Spring Boot's default `classpath:db/migration` path) and record history in `qbank_schema_history`. Your host application's `flyway_schema_history` and `db/migration` scripts remain untouched.

---

## 4. Spring Security Delegation (`HostSecurityBridge`)

When `spring-boot-starter-security` is on the classpath and `qbank.auth.mode` is `auto` or `host`:

1. `QBankAuthController` disables standalone login (`/api/v1/auth/login`), initial setup wizard, and `/api/v1/admin/users`.
2. `HostSecurityBridge` reads `SecurityContextHolder.getContext().getAuthentication()` and maps authorities using `qbank.roles.*` and `qbank.roles.room-authority-prefix`.
3. Student runtime routes (`/api/v1/student/**` excluding `/login` and `/public-sessions`) validate signed HMAC-SHA256 `qbs1.` student bearer tokens directly via `QBankSecurityAndMetricsFilter`. Permit the UI bundle (`/${basePath}/*.html`, `*.js`, `*.css`), `/${basePath}/api/v1/auth/info`, and `/${basePath}/api/v1/student/**` inside your host `SecurityFilterChain`:

```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/exam-bank/*.html", "/exam-bank/*.js", "/exam-bank/*.css").permitAll()
    .requestMatchers("/exam-bank/api/v1/auth/info", "/exam-bank/api/v1/student/**").permitAll()
    .anyRequest().authenticated()
);
```

See [examples/host-app](examples/host-app) for a complete runnable reference host portal.

---

## 5. Reading Frozen Papers & Domain Events

### Java Interface (`FrozenPaperProvider`)

Inject `FrozenPaperProvider` into your host exam delivery engine:

```java
FrozenPaperMeta meta = frozenPaperProvider.get(sessionId);
FrozenVersionView versionPaper = frozenPaperProvider.version(sessionId, "A");
List<AnswerKeyEntry> answerKey = frozenPaperProvider.key(sessionId, "A");
```

Security guarantees enforced internally by `DefaultFrozenPaperProvider`:
- Verifies SHA-256 digests of ciphertext before decrypting AES-256-GCM payloads.
- Rejects `version(...)` and `key(...)` with HTTP `403 FORBIDDEN` strictly outside the scheduled exam window `[scheduledStartAt, scheduledEndAt + maxGrantedExtraMinutes]`.

### Machine HTTP Engine Endpoint

If your exam engine runs as a separate service, configure `qbank.engine.service-token` and call:
- `GET {basePath}/api/v1/engine/sessions/{sessionId}`
- `GET {basePath}/api/v1/engine/sessions/{sessionId}/versions/{versionLabel}`
- `GET {basePath}/api/v1/engine/sessions/{sessionId}/versions/{versionLabel}/key`

Supply header `X-QBank-Engine-Token: <configured-token>`. Staff accounts (HOD, COE, INVIGILATOR) cannot access these endpoints.

### Domain Events (`SpringEventPublisherAdapter`)

Listen to `QBankEvent` records published synchronously via Spring's `ApplicationEventPublisher`:
- `ModuleImportedEvent`
- `PaperGeneratedEvent`
- `PaperFrozenEvent`
- `SessionStartedEvent`
- `SessionStoppedEvent`

### Overridable Hexagonal Ports

Any port declared in `QBankAutoConfiguration` with `@ConditionalOnMissingBean` can be replaced by declaring your own bean in the host context (`AuthPort`, `ClockPort`, `RandomPort`, `CryptoPort`, `StoragePort`, `ResponseSink`, `FrozenPaperProvider`, `EventPublisher`, `SpreadsheetPort`, `JsonCodecPort`, `PasswordHasherPort`).

---

## 6. Embedding the Student UI Component

Mount the standalone offline component into any host HTML page without external network calls:

```html
<div id="exam-root"></div>
<link rel="stylesheet" href="/exam-bank/qbank.css">
<script src="/exam-bank/qbank-api.js"></script>
<script src="/exam-bank/qbank-student-view.js"></script>
<script>
  QBankStudentView.mount(document.getElementById('exam-root'), {
    basePath: '/exam-bank',
    onFinished: (ack) => console.log('Exam submitted', ack)
  });
</script>
```
