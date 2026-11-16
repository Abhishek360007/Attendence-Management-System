package com.abhishek.attendance.scheduler;

import com.abhishek.attendance.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "attendance.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService syncService;

    public SyncScheduler(SyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${attendance.sync.cron}")
    public void run() {
        try {
            syncService.sync();
        } catch (SyncService.SyncInProgressException e) {
            log.debug("Skipping scheduled sync: previous run still in progress");
        }
    }
}
