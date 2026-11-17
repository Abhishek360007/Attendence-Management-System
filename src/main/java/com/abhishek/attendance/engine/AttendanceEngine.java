package com.abhishek.attendance.engine;

import com.abhishek.attendance.org.WorkSchedule;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns punches into days. Pure logic: no database, no clock (the current time is passed in).
 * <p>
 * Punches belong to the working day whose window contains them. A day's window starts 4 hours before the shift
 * and lasts 24 hours, so a night shift's punches after midnight still count for the day the shift started.
 */
public final class AttendanceEngine {

    /** How long before the shift start a day's punch window opens. */
    static final Duration WINDOW_LEAD = Duration.ofHours(4);

    public record LeaveDay(String typeName, boolean paid) {
    }

    /**
     * @param punches  the employee's effective punch times, sorted ascending; may extend beyond the range
     * @param holidays holiday name by date
     * @param leaves   approved leave by date
     * @param now      current office-local time
     */
    public static List<DayRecord> days(WorkSchedule schedule, LocalDate joinedOn, LocalDate leftOn,
                                       LocalDate from, LocalDate to, List<LocalDateTime> punches,
                                       Map<LocalDate, String> holidays, Map<LocalDate, LeaveDay> leaves,
                                       LocalDateTime now) {
        List<DayRecord> out = new ArrayList<>();
        LocalDate today = now.toLocalDate();
        int cursor = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (joinedOn != null && d.isBefore(joinedOn)) continue;
            if (leftOn != null && d.isAfter(leftOn)) continue;

            LocalDateTime start = d.atTime(schedule.startTime());
            LocalDateTime end = schedule.overnight() ? d.plusDays(1).atTime(schedule.endTime()) : d.atTime(schedule.endTime());
            LocalDateTime windowStart = start.minus(WINDOW_LEAD);
            LocalDateTime windowEnd = windowStart.plusDays(1);

            while (cursor < punches.size() && punches.get(cursor).isBefore(windowStart)) cursor++;
            List<LocalDateTime> dayPunches = new ArrayList<>();
            int i = cursor;
            while (i < punches.size() && punches.get(i).isBefore(windowEnd)) dayPunches.add(punches.get(i++));

            out.add(day(schedule, d, start, end, windowEnd, dayPunches, holidays.get(d), leaves.get(d), now, today));
        }
        return out;
    }

    private static DayRecord day(WorkSchedule s, LocalDate d, LocalDateTime start, LocalDateTime end,
                                 LocalDateTime windowEnd, List<LocalDateTime> punches, String holiday,
                                 LeaveDay leave, LocalDateTime now, LocalDate today) {
        LocalDateTime first = punches.isEmpty() ? null : punches.getFirst();
        LocalDateTime last = punches.size() > 1 ? punches.getLast() : null;
        long worked = last == null ? 0 : minutes(first, last);
        boolean dayOver = !now.isBefore(windowEnd);
        boolean shiftOver = !now.isBefore(end);

        boolean off = s.weeklyOffDays().contains(d.getDayOfWeek());
        if (holiday != null || off) {
            // Any work on a holiday or day off is overtime.
            long ot = worked >= s.overtimeMinMinutes() && worked > 0 ? worked : 0;
            return new DayRecord(d, holiday != null ? DayStatus.HOLIDAY : DayStatus.WEEKLY_OFF, start, end, first, last,
                    punches.size(), worked, 0, 0, ot, false, holiday, null);
        }
        if (leave != null) {
            return new DayRecord(d, DayStatus.LEAVE, start, end, first, last, punches.size(), worked, 0, 0, 0, false,
                    leave.typeName(), leave.paid());
        }
        if (d.isAfter(today)) {
            return new DayRecord(d, DayStatus.UPCOMING, start, end, null, null, 0, 0, 0, 0, 0, false, null, null);
        }
        if (punches.isEmpty()) {
            boolean notYetLate = now.isBefore(start.plusMinutes(s.lateGraceMinutes()));
            return new DayRecord(d, notYetLate ? DayStatus.UPCOMING : DayStatus.ABSENT, start, end, null, null, 0, 0,
                    0, 0, 0, false, null, null);
        }

        long late = first.isAfter(start.plusMinutes(s.lateGraceMinutes())) ? minutes(start, first) : 0;
        long early = 0;
        long overtime = 0;
        DayStatus status = DayStatus.PRESENT;
        if (last != null && shiftOver) {
            if (last.isBefore(end.minusMinutes(s.earlyGraceMinutes()))) {
                early = minutes(last, end);
            }
            long extra = worked - s.scheduledMinutes();
            if (extra > 0 && extra >= s.overtimeMinMinutes()) {
                overtime = extra;
            }
            if (s.halfDayMinutes() > 0 && worked < s.halfDayMinutes()) {
                status = DayStatus.HALF_DAY;
            }
        }
        boolean missingOut = last == null && dayOver;
        return new DayRecord(d, status, start, end, first, last, punches.size(), worked, late, early, overtime,
                missingOut, null, null);
    }

    private static long minutes(LocalDateTime a, LocalDateTime b) {
        return Duration.between(a, b).toMinutes();
    }

    private AttendanceEngine() {
    }
}
