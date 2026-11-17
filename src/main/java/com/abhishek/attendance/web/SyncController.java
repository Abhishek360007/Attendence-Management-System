package com.abhishek.attendance.web;

import com.abhishek.attendance.model.SyncRun;
import com.abhishek.attendance.service.AttendanceService;
import com.abhishek.attendance.service.SyncService;
import com.abhishek.attendance.repository.SyncRunRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;
    private final SyncRunRepository syncRunRepository;
    private final AttendanceService attendanceService;

    public SyncController(SyncService syncService, SyncRunRepository syncRunRepository,
                          AttendanceService attendanceService) {
        this.syncService = syncService;
        this.syncRunRepository = syncRunRepository;
        this.attendanceService = attendanceService;
    }

    /** Trigger a sync immediately instead of waiting for the schedule. */
    @PostMapping
    public SyncRun syncNow() {
        return syncService.sync();
    }

    @GetMapping("/status")
    public SyncStatus status() {
        return new SyncStatus(
                attendanceService.totalRecords(),
                attendanceService.lastSourceId(),
                attendanceService.latestPunchAt(),
                syncRunRepository.findLatest().orElse(null));
    }

    @GetMapping("/history")
    public List<SyncRun> history(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return syncRunRepository.findRecent(limit);
    }

    public record SyncStatus(long totalRecords, long lastSourceId, LocalDateTime latestPunchAt, SyncRun lastRun) {
    }
}
