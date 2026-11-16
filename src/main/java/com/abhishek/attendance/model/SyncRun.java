package com.abhishek.attendance.model;

import java.time.LocalDateTime;

public record SyncRun(
        Long id,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        long lastSourceId,
        int recordsSynced,
        Status status,
        String message) {

    public enum Status { SUCCESS, FAILED }
}
