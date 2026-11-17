package com.abhishek.attendance.model;

import java.time.LocalDateTime;

/**
 * A single punch stored in the application database.
 *
 * @param employeeId the device user number (USERINFO.Badgenumber), not the employee profile id
 * @param sourceId   the device database's id for the punch, used to sync incrementally and avoid duplicates;
 *                   negative for punches added by an approved correction
 * @param source     DEVICE or MANUAL
 * @param voided     replaced or removed by an approved correction; kept for the record but no longer counted
 */
public record AttendanceRecord(
        Long id,
        String employeeId,
        String employeeName,
        LocalDateTime checkTime,
        String checkType,
        Integer deviceId,
        long sourceId,
        String source,
        boolean voided) {

    public static AttendanceRecord fromDevice(String employeeId, String employeeName, LocalDateTime checkTime,
                                              String checkType, Integer deviceId, long sourceId) {
        return new AttendanceRecord(null, employeeId, employeeName, checkTime, checkType, deviceId, sourceId, "DEVICE", false);
    }
}
