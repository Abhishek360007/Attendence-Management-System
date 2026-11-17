package com.abhishek.attendance.engine;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One employee's day.
 *
 * @param note        holiday name or leave type
 * @param paidLeave   for LEAVE days: whether the leave type is paid
 * @param missingOut  only one punch on a finished day: the in or out punch is missing
 */
public record DayRecord(
        LocalDate date,
        DayStatus status,
        LocalDateTime scheduledStart,
        LocalDateTime scheduledEnd,
        LocalDateTime firstIn,
        LocalDateTime lastOut,
        int punches,
        long workedMinutes,
        long lateMinutes,
        long earlyMinutes,
        long overtimeMinutes,
        boolean missingOut,
        String note,
        Boolean paidLeave) {

    public boolean late() {
        return lateMinutes > 0;
    }

    public boolean leftEarly() {
        return earlyMinutes > 0;
    }

    /** A scheduled working day (not a holiday or weekly off). */
    public boolean workingDay() {
        return status != DayStatus.HOLIDAY && status != DayStatus.WEEKLY_OFF;
    }
}
