package com.abhishek.attendance.service;

import com.abhishek.attendance.config.AttendanceProperties;
import com.abhishek.attendance.model.AttendanceRecord;
import com.abhishek.attendance.model.DevicePunch;
import com.abhishek.attendance.model.SyncRun;
import com.abhishek.attendance.repository.AttendanceRepository;
import com.abhishek.attendance.repository.DeviceRepository;
import com.abhishek.attendance.repository.SyncRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Copies new punches from the ZKTeco database into the application database.
 * <p>
 * The highest synced device id is derived from the stored records themselves, so a failed run
 * never skips data and re-running is always safe.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final DeviceRepository deviceRepository;
    private final AttendanceRepository attendanceRepository;
    private final SyncRunRepository syncRunRepository;
    private final TransactionTemplate transactionTemplate;
    private final AttendanceProperties properties;
    private final ReentrantLock lock = new ReentrantLock();

    public SyncService(DeviceRepository deviceRepository,
                       AttendanceRepository attendanceRepository,
                       SyncRunRepository syncRunRepository,
                       TransactionTemplate transactionTemplate,
                       AttendanceProperties properties) {
        this.deviceRepository = deviceRepository;
        this.attendanceRepository = attendanceRepository;
        this.syncRunRepository = syncRunRepository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    /**
     * Runs one sync pass. Concurrent calls (scheduler + manual trigger) are rejected rather than queued.
     */
    public SyncRun sync() {
        if (!properties.sync().enabled()) {
            throw new SyncDisabledException();
        }
        if (!lock.tryLock()) {
            throw new SyncInProgressException();
        }
        try {
            return doSync();
        } finally {
            lock.unlock();
        }
    }

    private SyncRun doSync() {
        LocalDateTime started = LocalDateTime.now();
        long lastId = attendanceRepository.maxSourceId();
        SyncRun run;
        try {
            List<DevicePunch> punches = deviceRepository.fetchAfter(lastId, properties.sync().batchSize());
            List<AttendanceRecord> records = punches.stream()
                    .filter(p -> p.badgeNumber() != null && !p.badgeNumber().isBlank())
                    .map(DevicePunch::toRecord)
                    .toList();

            Integer inserted = transactionTemplate.execute(status -> attendanceRepository.insertAll(records));
            long newLastId = punches.isEmpty() ? lastId : punches.get(punches.size() - 1).autoIncrement();

            run = new SyncRun(null, started, LocalDateTime.now(), newLastId,
                    inserted == null ? 0 : inserted, SyncRun.Status.SUCCESS, null);
            if (run.recordsSynced() > 0) {
                log.info("Synced {} punch(es), device id {} -> {}", run.recordsSynced(), lastId, newLastId);
            } else {
                log.debug("No new punches after device id {}", lastId);
            }
        } catch (Exception e) {
            log.error("Attendance sync failed", e);
            run = new SyncRun(null, started, LocalDateTime.now(), lastId, 0, SyncRun.Status.FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Only persist runs that did something, so the log is not flooded every minute.
        if (run.status() == SyncRun.Status.FAILED || run.recordsSynced() > 0) {
            syncRunRepository.save(run);
        }
        return run;
    }

    public static class SyncDisabledException extends RuntimeException {
        public SyncDisabledException() {
            super("Sync is disabled on this instance (SYNC_ENABLED=false). Run sync on the machine that has the ZKTeco database.");
        }
    }

    public static class SyncInProgressException extends RuntimeException {
        public SyncInProgressException() {
            super("A sync is already running");
        }
    }
}
