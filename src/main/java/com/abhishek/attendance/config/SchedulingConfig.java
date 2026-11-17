package com.abhishek.attendance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled work: the device sync (only where SYNC_ENABLED=true) and notification jobs (NOTIFICATIONS_ENABLED).
 * Async is used to send notification emails after a request has finished.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
