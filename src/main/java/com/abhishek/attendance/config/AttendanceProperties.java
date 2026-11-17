package com.abhishek.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey         when non-blank, machine clients may authenticate with the {@code X-API-Key} header;
 *                       it is also required once to create the first admin account
 * @param bootstrapAdmin optional admin account created on startup when there are no users
 */
@ConfigurationProperties(prefix = "attendance")
public record AttendanceProperties(String apiKey, BootstrapAdmin bootstrapAdmin, Device device, Sync sync) {

    public boolean apiKeyEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public record BootstrapAdmin(String email, String password) {
        public boolean configured() {
            return email != null && !email.isBlank() && password != null && !password.isBlank();
        }
    }

    public record Device(String mdbPath) {
        public String jdbcUrl() {
            return "jdbc:ucanaccess://" + mdbPath + ";memory=false;openExclusive=false";
        }
    }

    /** @param lookbackDays CHECK_TIME layout only: days rescanned each run to catch punches downloaded late */
    public record Sync(boolean enabled, String cron, int batchSize, int lookbackDays) {
    }
}
