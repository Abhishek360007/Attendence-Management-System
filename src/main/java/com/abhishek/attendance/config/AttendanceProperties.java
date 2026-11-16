package com.abhishek.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey when non-blank, required as the {@code X-API-Key} header on every {@code /api/**} request
 */
@ConfigurationProperties(prefix = "attendance")
public record AttendanceProperties(String apiKey, Device device, Sync sync) {

    public boolean apiKeyEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public record Device(String mdbPath) {
        public String jdbcUrl() {
            return "jdbc:ucanaccess://" + mdbPath + ";memory=false;openExclusive=false";
        }
    }

    public record Sync(boolean enabled, String cron, int batchSize) {
    }
}
