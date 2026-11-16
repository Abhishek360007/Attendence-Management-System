package com.abhishek.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "attendance")
public record AttendanceProperties(Device device, Sync sync) {

    public record Device(String mdbPath) {
        public String jdbcUrl() {
            return "jdbc:ucanaccess://" + mdbPath + ";memory=false;openExclusive=false";
        }
    }

    public record Sync(boolean enabled, String cron, int batchSize) {
    }
}
