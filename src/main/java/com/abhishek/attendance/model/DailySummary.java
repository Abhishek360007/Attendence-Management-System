package com.abhishek.attendance.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * First-in / last-out view of an employee's day.
 */
public record DailySummary(
        String employeeId,
        String employeeName,
        LocalDate date,
        LocalDateTime firstIn,
        LocalDateTime lastOut,
        int punches,
        long workedMinutes) {

    public static DailySummary of(String employeeId, String employeeName, LocalDate date,
                                  LocalDateTime firstIn, LocalDateTime lastOut, int punches) {
        long minutes = (firstIn != null && lastOut != null && punches > 1)
                ? Duration.between(firstIn, lastOut).toMinutes()
                : 0;
        return new DailySummary(employeeId, employeeName, date, firstIn, punches > 1 ? lastOut : null, punches, minutes);
    }
}
