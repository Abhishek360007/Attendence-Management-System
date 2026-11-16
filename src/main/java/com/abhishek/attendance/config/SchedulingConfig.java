package com.abhishek.attendance.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "attendance.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
