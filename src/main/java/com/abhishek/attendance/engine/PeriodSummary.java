package com.abhishek.attendance.engine;

import java.util.List;

/**
 * Totals over a period, counting only days up to today.
 *
 * @param workingDays       scheduled working days (excludes holidays and weekly offs)
 * @param presentDays       full days present plus half of each half day
 * @param absentDays        absent days plus half of each half day
 * @param attendancePercent present days / (working days - leave days), or null when there were no such days
 */
public record PeriodSummary(
        int workingDays,
        double presentDays,
        int halfDays,
        double absentDays,
        int leaveDays,
        int paidLeaveDays,
        int unpaidLeaveDays,
        int holidays,
        int weeklyOffs,
        int lateDays,
        long lateMinutes,
        int earlyDays,
        long earlyMinutes,
        long overtimeMinutes,
        long workedMinutes,
        int missingPunchDays,
        Double attendancePercent) {

    public static PeriodSummary of(List<DayRecord> days) {
        int working = 0, half = 0, absent = 0, full = 0, leave = 0, paid = 0, unpaid = 0, holidays = 0, offs = 0;
        int lateDays = 0, earlyDays = 0, missing = 0;
        long late = 0, early = 0, ot = 0, worked = 0;
        for (DayRecord d : days) {
            ot += d.overtimeMinutes();
            worked += d.workedMinutes();
            switch (d.status()) {
                case UPCOMING -> { continue; }
                case HOLIDAY -> { holidays++; continue; }
                case WEEKLY_OFF -> { offs++; continue; }
                case LEAVE -> {
                    leave++;
                    if (Boolean.FALSE.equals(d.paidLeave())) unpaid++; else paid++;
                }
                case ABSENT -> absent++;
                case HALF_DAY -> half++;
                case PRESENT -> full++;
            }
            working++;
            if (d.late()) { lateDays++; late += d.lateMinutes(); }
            if (d.leftEarly()) { earlyDays++; early += d.earlyMinutes(); }
            if (d.missingOut()) missing++;
        }
        double present = full + half * 0.5;
        int expected = working - leave;
        Double pct = expected > 0 ? Math.round(present * 1000.0 / expected) / 10.0 : null;
        return new PeriodSummary(working, present, half, absent + half * 0.5, leave, paid, unpaid, holidays, offs,
                lateDays, late, earlyDays, early, ot, worked, missing, pct);
    }
}
