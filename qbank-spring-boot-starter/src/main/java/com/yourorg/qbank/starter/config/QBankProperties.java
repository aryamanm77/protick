package com.yourorg.qbank.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qbank")
public class QBankProperties {

    private boolean enabled = true;
    private String basePath = "/qbank";
    private String storageDir = "./data/qbank-storage";
    private int retentionDays = 365;

    private final Auth auth = new Auth();
    private final Roles roles = new Roles();
    private final Datasource datasource = new Datasource();
    private final Security security = new Security();
    private final Backup backup = new Backup();
    private final Engine engine = new Engine();

    public String normalizedBasePath() {
        String p = (basePath == null || basePath.isBlank()) ? "/qbank" : basePath.trim();
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return "/".equals(p) ? "" : p;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public Auth getAuth() { return auth; }
    public Roles getRoles() { return roles; }
    public Datasource getDatasource() { return datasource; }
    public Security getSecurity() { return security; }
    public Backup getBackup() { return backup; }
    public Engine getEngine() { return engine; }

    public enum AuthMode {
        AUTO, STANDALONE, HOST
    }

    public static class Auth {
        private AuthMode mode = AuthMode.AUTO;
        public AuthMode getMode() { return mode; }
        public void setMode(AuthMode mode) { this.mode = mode; }
    }

    public static class Roles {
        private String admin = "ROLE_ADMIN,ADMIN,ROLE_QBANK_ADMIN";
        private String hod = "ROLE_HOD,HOD,ROLE_QBANK_HOD";
        private String coe = "ROLE_COE,COE,ROLE_QBANK_COE";
        private String invigilator = "ROLE_INVIGILATOR,INVIGILATOR,ROLE_QBANK_INVIGILATOR";
        private String roomAuthorityPrefix = "ROOM:";

        public String getAdmin() { return admin; }
        public void setAdmin(String admin) { this.admin = admin; }
        public String getHod() { return hod; }
        public void setHod(String hod) { this.hod = hod; }
        public String getCoe() { return coe; }
        public void setCoe(String coe) { this.coe = coe; }
        public String getInvigilator() { return invigilator; }
        public void setInvigilator(String invigilator) { this.invigilator = invigilator; }
        public String getRoomAuthorityPrefix() { return roomAuthorityPrefix; }
        public void setRoomAuthorityPrefix(String roomAuthorityPrefix) { this.roomAuthorityPrefix = roomAuthorityPrefix; }
    }

    public static class Datasource {
        private String url = "";
        private String username = "";
        private String password = "";
        private String driverClassName = "";
        private String dialect = "auto";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
        public String getDialect() { return dialect; }
        public void setDialect(String dialect) { this.dialect = dialect; }
    }

    public static class Security {
        private String encryptionKeyBase64 = "";
        private String encryptionKeyFile = "";
        private String signingKeyBase64 = "";
        private String signingKeyFile = "";
        private int studentTokenTtlMinutes = 240;
        private int maxUploadBytes = 10 * 1024 * 1024;
        private int loginRateLimitPerMinute = 30;
        private String csp = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

        public String getEncryptionKeyBase64() { return encryptionKeyBase64; }
        public void setEncryptionKeyBase64(String encryptionKeyBase64) { this.encryptionKeyBase64 = encryptionKeyBase64; }
        public String getEncryptionKeyFile() { return encryptionKeyFile; }
        public void setEncryptionKeyFile(String encryptionKeyFile) { this.encryptionKeyFile = encryptionKeyFile; }
        public String getSigningKeyBase64() { return signingKeyBase64; }
        public void setSigningKeyBase64(String signingKeyBase64) { this.signingKeyBase64 = signingKeyBase64; }
        public String getSigningKeyFile() { return signingKeyFile; }
        public void setSigningKeyFile(String signingKeyFile) { this.signingKeyFile = signingKeyFile; }
        public int getStudentTokenTtlMinutes() { return studentTokenTtlMinutes; }
        public void setStudentTokenTtlMinutes(int studentTokenTtlMinutes) { this.studentTokenTtlMinutes = studentTokenTtlMinutes; }
        public int getMaxUploadBytes() { return maxUploadBytes; }
        public void setMaxUploadBytes(int maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
        public int getLoginRateLimitPerMinute() { return loginRateLimitPerMinute; }
        public void setLoginRateLimitPerMinute(int loginRateLimitPerMinute) { this.loginRateLimitPerMinute = loginRateLimitPerMinute; }
        public String getCsp() { return csp; }
        public void setCsp(String csp) { this.csp = csp; }
    }

    public static class Backup {
        private boolean enabled = true;
        private String dailyCron = "0 0 2 * * *";
        private int liveSessionIntervalMinutes = 5;
        private int keepLast = 14;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDailyCron() { return dailyCron; }
        public void setDailyCron(String dailyCron) { this.dailyCron = dailyCron; }
        public int getLiveSessionIntervalMinutes() { return liveSessionIntervalMinutes; }
        public void setLiveSessionIntervalMinutes(int liveSessionIntervalMinutes) { this.liveSessionIntervalMinutes = liveSessionIntervalMinutes; }
        public int getKeepLast() { return keepLast; }
        public void setKeepLast(int keepLast) { this.keepLast = keepLast; }
    }

    public static class Engine {
        private String serviceToken = "";
        public String getServiceToken() { return serviceToken; }
        public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
    }
}
