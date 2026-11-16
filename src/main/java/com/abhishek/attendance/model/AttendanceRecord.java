package com.abhishek.attendance.model;

import java.time.LocalDateTime;

/**
 * A single punch stored in the application database.
 *
 * @param sourceId the device database's auto-increment id, used to sync incrementally and avoid duplicates
 */
public record AttendanceRecord(
        Long id,
        String employeeId,
        String employeeName,
        LocalDateTime checkTime,
        String checkType,
        Integer deviceId,
        long sourceId) {
}
