package com.yourorg.qbank.starter.config;

import com.yourorg.qbank.core.port.*;
import com.yourorg.qbank.core.service.*;
import com.yourorg.qbank.persistence.JdbcTransactionManager;
import com.yourorg.qbank.persistence.QBankDialect;
import com.yourorg.qbank.persistence.QBankFlywayMigrator;
import com.yourorg.qbank.persistence.adapter.*;
import com.yourorg.qbank.persistence.backup.EncryptedBackupService;
import com.yourorg.qbank.persistence.jdbc.*;
import com.yourorg.qbank.starter.security.CompositeAuthPort;
import com.yourorg.qbank.starter.security.KeyMaterialResolver;
import com.yourorg.qbank.starter.security.QBankSecurityAndMetricsFilter;
import com.yourorg.qbank.starter.security.StudentTokenCodec;
import com.yourorg.qbank.starter.service.QBankBackupScheduler;
import com.yourorg.qbank.starter.service.QBankHealthMonitor;
import com.yourorg.qbank.starter.service.SpringEventPublisherAdapter;
import com.yourorg.qbank.starter.web.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Drop-in Spring Boot 3 auto-configuration for the QBank module.
 * Activates automatically unless {@code qbank.enabled=false}.
 */
@AutoConfiguration(
        after = DataSourceAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
)
@ConditionalOnProperty(prefix = "qbank", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(QBankProperties.class)
@EnableScheduling
public class QBankAutoConfiguration {

    @Bean
    public QBankDataSourceHolder qbankDataSourceHolder(
            QBankProperties props,
            ObjectProvider<DataSource> hostDataSourceProvider
    ) {
        String customUrl = props.getDatasource().getUrl();
        if (StringUtils.hasText(customUrl)) {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(customUrl.trim());
            if (StringUtils.hasText(props.getDatasource().getUsername())) {
                hc.setUsername(props.getDatasource().getUsername().trim());
            }
            if (props.getDatasource().getPassword() != null && !props.getDatasource().getPassword().isEmpty()) {
                hc.setPassword(props.getDatasource().getPassword());
            }
            if (StringUtils.hasText(props.getDatasource().getDriverClassName())) {
                hc.setDriverClassName(props.getDatasource().getDriverClassName().trim());
            }
            hc.setPoolName("qbank-pool");
            String lowerUrl = customUrl.trim().toLowerCase();
            if (lowerUrl.startsWith("jdbc:sqlite:")) {
                hc.setMaximumPoolSize(1);
                hc.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;");
            } else if (lowerUrl.startsWith("jdbc:h2:")) {
                hc.setConnectionInitSql("SET WRITE_DELAY 0;");
            }
            HikariDataSource dedicated = new HikariDataSource(hc);
            return new QBankDataSourceHolder(dedicated, true);
        }
        DataSource hostDs = hostDataSourceProvider.getIfAvailable();
        if (hostDs == null) {
            throw new IllegalStateException(
                    "QBank requires a DataSource: configure spring.datasource.* or qbank.datasource.*");
        }
        return new QBankDataSourceHolder(hostDs, false);
    }

    @Bean
    public QBankDialect qbankDialect(QBankDataSourceHolder dsHolder, QBankProperties props) {
        return QBankDialect.detect(dsHolder.dataSource(), props.getDatasource().getDialect());
    }

    @Bean
    @ConditionalOnMissingBean(JdbcTransactionManager.class)
    public JdbcTransactionManager qbankTransactionManager(QBankDataSourceHolder dsHolder, QBankDialect dialect) {
        QBankFlywayMigrator.migrate(dsHolder.dataSource(), dialect);
        return new JdbcTransactionManager(dsHolder.dataSource(), dialect);
    }

    @Bean
    @ConditionalOnMissingBean(ClockPort.class)
    public ClockPort qbankClockPort() {
        return new SystemClockAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(RandomPort.class)
    public RandomPort qbankRandomPort() {
        return new SecureRandomAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(JsonCodecPort.class)
    public JsonCodecPort qbankJsonCodecPort() {
        return new JacksonJsonCodecAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(PasswordHasherPort.class)
    public PasswordHasherPort qbankPasswordHasherPort() {
        return new BcryptPasswordHasherAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(SpreadsheetPort.class)
    public SpreadsheetPort qbankSpreadsheetPort() {
        return new HardenedPoiSpreadsheetAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(StoragePort.class)
    public StoragePort qbankStoragePort(QBankProperties props) {
        return new LocalFileStorageAdapter(Path.of(props.getStorageDir()).toAbsolutePath().normalize());
    }

    @Bean
    @ConditionalOnMissingBean(CryptoPort.class)
    public CryptoPort qbankCryptoPort(QBankProperties props) {
        byte[] aesKey = KeyMaterialResolver.resolveKeyBytes(
                props.getSecurity().getEncryptionKeyBase64(),
                props.getSecurity().getEncryptionKeyFile(),
                "QBANK_ENCRYPTION_KEY",
                "QBank AES-256 encryption key (qbank.security.encryption-key-*)",
                32
        );
        byte[] hmacKey = KeyMaterialResolver.resolveKeyBytes(
                props.getSecurity().getSigningKeyBase64(),
                props.getSecurity().getSigningKeyFile(),
                "QBANK_SIGNING_KEY",
                "QBank HMAC-SHA256 signing key (qbank.security.signing-key-*)",
                32
        );
        return new AesGcmCryptoAdapter(aesKey, hmacKey);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher qbankEventPublisher(ApplicationEventPublisher springPublisher) {
        return new SpringEventPublisherAdapter(springPublisher);
    }

    @Bean
    @ConditionalOnMissingBean(SubjectRepository.class)
    public SubjectRepository qbankSubjectRepository(JdbcTransactionManager tx) {
        return new JdbcSubjectRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(ModuleRepository.class)
    public ModuleRepository qbankModuleRepository(JdbcTransactionManager tx) {
        return new JdbcModuleRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(McqRepository.class)
    public McqRepository qbankMcqRepository(JdbcTransactionManager tx) {
        return new JdbcMcqRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(ImportPreviewRepository.class)
    public ImportPreviewRepository qbankImportPreviewRepository(JdbcTransactionManager tx) {
        return new JdbcImportPreviewRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(ExamRepository.class)
    public ExamRepository qbankExamRepository(JdbcTransactionManager tx, JsonCodecPort jsonCodec) {
        return new JdbcExamRepository(tx, jsonCodec);
    }

    @Bean
    @ConditionalOnMissingBean(ExamStudentRepository.class)
    public ExamStudentRepository qbankExamStudentRepository(JdbcTransactionManager tx) {
        return new JdbcExamStudentRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(StudentResponseRepository.class)
    public StudentResponseRepository qbankStudentResponseRepository(JdbcTransactionManager tx) {
        return new JdbcStudentResponseRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(ExtraTimeGrantRepository.class)
    public ExtraTimeGrantRepository qbankExtraTimeGrantRepository(JdbcTransactionManager tx) {
        return new JdbcExtraTimeGrantRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(UserAccountRepository.class)
    public UserAccountRepository qbankUserAccountRepository(JdbcTransactionManager tx) {
        return new JdbcUserAccountRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(UserSessionRepository.class)
    public UserSessionRepository qbankUserSessionRepository(JdbcTransactionManager tx) {
        return new JdbcUserSessionRepository(tx);
    }

    @Bean
    @ConditionalOnMissingBean(AuditPort.class)
    public AuditPort qbankAuditPort(JdbcTransactionManager tx, CryptoPort cryptoPort, ClockPort clockPort) {
        return new JdbcAuditPort(tx, cryptoPort, clockPort);
    }

    @Bean
    @ConditionalOnMissingBean(ResponseSink.class)
    public ResponseSink qbankResponseSink(StudentResponseRepository responseRepository) {
        return responseRepository::upsertResponse;
    }

    @Bean
    public QBankAuthModeState qbankAuthModeState(QBankProperties props, ApplicationContext ctx) {
        QBankProperties.AuthMode configured = props.getAuth().getMode();
        ClassLoader cl = ctx.getClassLoader();
        boolean hasSpringSecurity = ClassUtils.isPresent(
                "org.springframework.security.core.context.SecurityContextHolder", cl);

        boolean hostModeActive;
        if (configured == QBankProperties.AuthMode.HOST) {
            if (!hasSpringSecurity) {
                throw new IllegalStateException("qbank.auth.mode=host requires Spring Security on the classpath");
            }
            hostModeActive = true;
        } else if (configured == QBankProperties.AuthMode.STANDALONE) {
            hostModeActive = false;
        } else {
            hostModeActive = hasSpringSecurity;
        }
        return new QBankAuthModeState(hostModeActive);
    }

    @Bean
    @ConditionalOnMissingBean(AuthPort.class)
    public AuthPort qbankAuthPort(QBankProperties props, QBankAuthModeState authModeState, ApplicationContext ctx) {
        AuthPort hostBridge = null;
        if (authModeState.hostModeActive()) {
            try {
                Class<?> bridgeClass = ClassUtils.forName(
                        "com.yourorg.qbank.starter.security.HostSecurityBridge",
                        ctx.getClassLoader()
                );
                hostBridge = (AuthPort) bridgeClass
                        .getConstructor(QBankProperties.Roles.class)
                        .newInstance(props.getRoles());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize Spring Security HostSecurityBridge", e);
            }
        }
        return new CompositeAuthPort(hostBridge);
    }

    @Bean
    @ConditionalOnMissingBean(FrozenPaperProvider.class)
    public DefaultFrozenPaperProvider qbankFrozenPaperProvider(
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            ClockPort clockPort,
            AuditPort auditPort
    ) {
        return new DefaultFrozenPaperProvider(
                examRepository, studentRepository, cryptoPort, jsonCodec, clockPort, auditPort);
    }

    @Bean
    @ConditionalOnMissingBean(CoreAuditService.class)
    public CoreAuditService qbankCoreAuditService(AuditPort auditPort, JsonCodecPort jsonCodec, AuthPort authPort) {
        return new CoreAuditService(auditPort, jsonCodec, authPort);
    }

    @Bean
    @ConditionalOnMissingBean(CoreSubjectService.class)
    public CoreSubjectService qbankCoreSubjectService(
            SubjectRepository subjectRepository,
            ModuleRepository moduleRepository,
            TransactionPort tx,
            AuditPort auditPort,
            JsonCodecPort jsonCodec,
            ClockPort clockPort,
            AuthPort authPort
    ) {
        return new CoreSubjectService(subjectRepository, moduleRepository, tx, auditPort, jsonCodec, clockPort, authPort);
    }

    @Bean
    @ConditionalOnMissingBean(CoreImportService.class)
    public CoreImportService qbankCoreImportService(
            SubjectRepository subjectRepository,
            ModuleRepository moduleRepository,
            McqRepository mcqRepository,
            ExamRepository examRepository,
            ImportPreviewRepository previewRepository,
            SpreadsheetPort spreadsheetPort,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort tx,
            AuditPort auditPort,
            EventPublisher eventPublisher,
            AuthPort authPort,
            QBankProperties props
    ) {
        return new CoreImportService(
                subjectRepository, moduleRepository, mcqRepository, examRepository, previewRepository,
                spreadsheetPort, cryptoPort, jsonCodec, randomPort, clockPort, tx,
                auditPort, eventPublisher, authPort, props.getSecurity().getMaxUploadBytes()
        );
    }

    @Bean
    @ConditionalOnMissingBean(CoreExamPaperService.class)
    public CoreExamPaperService qbankCoreExamPaperService(
            SubjectRepository subjectRepository,
            ModuleRepository moduleRepository,
            McqRepository mcqRepository,
            ExamRepository examRepository,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort tx,
            AuditPort auditPort,
            EventPublisher eventPublisher,
            AuthPort authPort,
            DefaultFrozenPaperProvider frozenPaperProvider
    ) {
        return new CoreExamPaperService(
                subjectRepository, moduleRepository, mcqRepository, examRepository,
                cryptoPort, jsonCodec, randomPort, clockPort, tx,
                auditPort, eventPublisher, authPort, frozenPaperProvider
        );
    }

    @Bean
    @ConditionalOnMissingBean(CoreStudentRosterAndInvigilatorService.class)
    public CoreStudentRosterAndInvigilatorService qbankCoreRosterAndInvigilatorService(
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            StudentResponseRepository responseRepository,
            ExtraTimeGrantRepository extraTimeGrantRepository,
            PasswordHasherPort passwordHasher,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort tx,
            AuditPort auditPort,
            JsonCodecPort jsonCodec,
            AuthPort authPort
    ) {
        return new CoreStudentRosterAndInvigilatorService(
                examRepository, studentRepository, responseRepository, extraTimeGrantRepository,
                passwordHasher, randomPort, clockPort, tx, auditPort, jsonCodec, authPort
        );
    }

    @Bean
    @ConditionalOnMissingBean(CoreStudentExamService.class)
    public CoreStudentExamService qbankCoreStudentExamService(
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            StudentResponseRepository responseRepository,
            FrozenPaperProvider frozenPaperProvider,
            ResponseSink responseSink,
            PasswordHasherPort passwordHasher,
            CryptoPort cryptoPort,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort tx,
            AuditPort auditPort,
            JsonCodecPort jsonCodec,
            AuthPort authPort
    ) {
        return new CoreStudentExamService(
                examRepository, studentRepository, responseRepository, frozenPaperProvider, responseSink,
                passwordHasher, cryptoPort, randomPort, clockPort, tx, auditPort, jsonCodec, authPort
        );
    }

    @Bean
    @ConditionalOnMissingBean(CoreResultsExportService.class)
    public CoreResultsExportService qbankCoreResultsExportService(
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            StudentResponseRepository responseRepository,
            DefaultFrozenPaperProvider frozenPaperProvider,
            SpreadsheetPort spreadsheetPort,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            AuditPort auditPort,
            AuthPort authPort
    ) {
        return new CoreResultsExportService(
                examRepository, studentRepository, responseRepository, frozenPaperProvider,
                spreadsheetPort, cryptoPort, jsonCodec, auditPort, authPort
        );
    }

    @Bean
    public QBankStandaloneAuthHolder qbankStandaloneAuthHolder(
            QBankAuthModeState authModeState,
            UserAccountRepository userRepo,
            UserSessionRepository sessionRepo,
            PasswordHasherPort passwordHasher,
            CryptoPort cryptoPort,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort tx,
            AuditPort auditPort,
            JsonCodecPort jsonCodec,
            AuthPort authPort
    ) {
        if (authModeState.hostModeActive()) {
            return new QBankStandaloneAuthHolder(null);
        }
        return new QBankStandaloneAuthHolder(new CoreStandaloneAuthService(
                userRepo, sessionRepo, passwordHasher, cryptoPort, randomPort,
                clockPort, tx, auditPort, jsonCodec, authPort
        ));
    }

    @Bean
    @ConditionalOnMissingBean(EncryptedBackupService.class)
    public EncryptedBackupService qbankEncryptedBackupService(
            JdbcTransactionManager tx,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            StoragePort storagePort,
            ClockPort clockPort,
            AuditPort auditPort
    ) {
        return new EncryptedBackupService(tx, cryptoPort, jsonCodec, storagePort, clockPort, auditPort);
    }

    @Bean
    @ConditionalOnMissingBean(QBankHealthMonitor.class)
    public QBankHealthMonitor qbankHealthMonitor(
            QBankDataSourceHolder dsHolder,
            StoragePort storagePort,
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            ClockPort clockPort
    ) {
        return new QBankHealthMonitor(dsHolder.dataSource(), storagePort, examRepository, studentRepository, clockPort);
    }

    @Bean
    @ConditionalOnMissingBean(QBankBackupScheduler.class)
    public QBankBackupScheduler qbankBackupScheduler(
            QBankProperties props,
            EncryptedBackupService backupService,
            ExamRepository examRepository,
            ImportPreviewRepository previewRepository,
            ClockPort clockPort
    ) {
        return new QBankBackupScheduler(props, backupService, examRepository, previewRepository, clockPort);
    }

    @Bean
    @ConditionalOnMissingBean(StudentTokenCodec.class)
    public StudentTokenCodec qbankStudentTokenCodec(CryptoPort cryptoPort, JsonCodecPort jsonCodec, ClockPort clockPort) {
        return new StudentTokenCodec(cryptoPort, jsonCodec, clockPort);
    }

    @Bean
    @ConditionalOnMissingBean(QBankWebMvcConfigurer.class)
    public QBankWebMvcConfigurer qbankWebMvcConfigurer(QBankProperties props) {
        return new QBankWebMvcConfigurer(props);
    }

    @Bean
    public FilterRegistrationBean<QBankSecurityAndMetricsFilter> qbankSecurityFilterRegistration(
            QBankProperties props,
            StudentTokenCodec studentTokenCodec,
            QBankStandaloneAuthHolder standaloneAuthHolder,
            QBankHealthMonitor healthMonitor
    ) {
        QBankSecurityAndMetricsFilter filter = new QBankSecurityAndMetricsFilter(
                props, studentTokenCodec, standaloneAuthHolder.service(), healthMonitor);
        FilterRegistrationBean<QBankSecurityAndMetricsFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(10);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean(QBankExceptionHandler.class)
    public QBankExceptionHandler qbankExceptionHandler(QBankHealthMonitor healthMonitor) {
        return new QBankExceptionHandler(healthMonitor);
    }

    @Bean
    @ConditionalOnMissingBean(QBankAuthController.class)
    public QBankAuthController qbankAuthController(
            QBankProperties props,
            AuthPort authPort,
            QBankStandaloneAuthHolder standaloneAuthHolder,
            UserAccountRepository userAccountRepository,
            ClockPort clockPort,
            QBankAuthModeState authModeState
    ) {
        return new QBankAuthController(
                props, authPort, standaloneAuthHolder.service(), userAccountRepository, clockPort, authModeState.hostModeActive());
    }

    @Bean
    @ConditionalOnMissingBean(QBankSubjectController.class)
    public QBankSubjectController qbankSubjectController(CoreSubjectService subjectService) {
        return new QBankSubjectController(subjectService);
    }

    @Bean
    @ConditionalOnMissingBean(QBankHodController.class)
    public QBankHodController qbankHodController(CoreImportService importService) {
        return new QBankHodController(importService);
    }

    @Bean
    @ConditionalOnMissingBean(QBankCoeController.class)
    public QBankCoeController qbankCoeController(
            CoreExamPaperService examService,
            CoreStudentRosterAndInvigilatorService rosterService
    ) {
        return new QBankCoeController(examService, rosterService);
    }

    @Bean
    @ConditionalOnMissingBean(QBankInvigilatorController.class)
    public QBankInvigilatorController qbankInvigilatorController(CoreStudentRosterAndInvigilatorService rosterService) {
        return new QBankInvigilatorController(rosterService);
    }

    @Bean
    @ConditionalOnMissingBean(QBankStudentController.class)
    public QBankStudentController qbankStudentController(
            CoreStudentExamService studentService,
            StudentTokenCodec tokenCodec,
            ExamRepository examRepository,
            AuthPort authPort,
            ClockPort clockPort,
            QBankProperties props
    ) {
        return new QBankStudentController(studentService, tokenCodec, examRepository, authPort, clockPort, props);
    }

    @Bean
    @ConditionalOnMissingBean(QBankAdminController.class)
    public QBankAdminController qbankAdminController(
            QBankProperties props,
            AuthPort authPort,
            QBankStandaloneAuthHolder standaloneAuthHolder,
            QBankAuthModeState authModeState,
            CoreAuditService auditService,
            CoreResultsExportService resultsExportService,
            EncryptedBackupService backupService,
            QBankHealthMonitor healthMonitor,
            StudentResponseRepository responseRepository,
            ClockPort clockPort
    ) {
        return new QBankAdminController(
                props, authPort, standaloneAuthHolder.service(), authModeState.hostModeActive(),
                auditService, resultsExportService, backupService, healthMonitor, responseRepository, clockPort
        );
    }

    @Bean
    @ConditionalOnMissingBean(QBankEngineController.class)
    public QBankEngineController qbankEngineController(
            QBankProperties props,
            CryptoPort cryptoPort,
            FrozenPaperProvider frozenPaperProvider
    ) {
        return new QBankEngineController(props, cryptoPort, frozenPaperProvider);
    }

    /**
     * Ensures Spring Boot's host Flyway migration runs before QBank's isolated migration
     * whenever both share the host DataSource and empty schema.
     */
    @Bean
    public static BeanFactoryPostProcessor qbankFlywayOrderingPostProcessor() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition("flywayInitializer")
                    && beanFactory.containsBeanDefinition("qbankTransactionManager")) {
                BeanDefinition bd = beanFactory.getBeanDefinition("qbankTransactionManager");
                String[] existing = bd.getDependsOn();
                if (existing == null || existing.length == 0) {
                    bd.setDependsOn("flywayInitializer");
                } else if (!Arrays.asList(existing).contains("flywayInitializer")) {
                    String[] updated = Arrays.copyOf(existing, existing.length + 1);
                    updated[existing.length] = "flywayInitializer";
                    bd.setDependsOn(updated);
                }
            }
        };
    }

    public static final class QBankDataSourceHolder implements DisposableBean {
        private final DataSource dataSource;
        private final boolean owned;

        public QBankDataSourceHolder(DataSource dataSource, boolean owned) {
            this.dataSource = Objects.requireNonNull(dataSource);
            this.owned = owned;
        }

        public DataSource dataSource() {
            return dataSource;
        }

        @Override
        public void destroy() {
            if (owned && dataSource instanceof HikariDataSource hikari) {
                hikari.close();
            }
        }
    }

    public record QBankAuthModeState(boolean hostModeActive) {}

    public record QBankStandaloneAuthHolder(CoreStandaloneAuthService service) {}
}
