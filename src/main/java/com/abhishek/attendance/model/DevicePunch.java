package com.abhishek.attendance.model;

import java.time.LocalDateTime;

/**
 * A raw row from the ZKTeco CHECKINOUT table joined with USERINFO.
 */
public record DevicePunch(
        long autoIncrement,
        String badgeNumber,
        String name,
        LocalDateTime checkTime,
        String checkType,
        Integer sensorId) {

    public AttendanceRecord toRecord() {
        return AttendanceRecord.fromDevice(badgeNumber, name, checkTime, checkType, sensorId, autoIncrement);
    }
}
