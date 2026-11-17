package com.abhishek.attendance.engine;

import com.abhishek.attendance.org.WorkSchedule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceEngineTest {

    // 09:00-18:00, 10 min grace each way, overtime from 30 min, half day under 4 h, Sundays off.
    static final WorkSchedule DAY_SHIFT = new WorkSchedule(1L, "General", LocalTime.of(9, 0), LocalTime.of(18, 0),
            10, 10, 30, 240, "SUNDAY", true);
    // 22:00-06:00 night shift.
    static final WorkSchedule NIGHT_SHIFT = new WorkSchedule(2L, "Night", LocalTime.of(22, 0), LocalTime.of(6, 0),
            10, 10, 30, 240, "SUNDAY", false);

    static final LocalDate MON = LocalDate.of(2026, 11, 2);
    static final LocalDateTime LATER = MON.plusDays(30).atTime(12, 0);

    static DayRecord one(WorkSchedule s, LocalDate d, LocalDateTime now, LocalDateTime... punches) {
        return AttendanceEngine.days(s, null, null, d, d, List.of(punches), Map.of(), Map.of(), now).getFirst();
    }

    @Test
    void onTimeFullDay() {
        DayRecord d = one(DAY_SHIFT, MON, LATER, MON.atTime(8, 55), MON.atTime(13, 0), MON.atTime(13, 30), MON.atTime(18, 5));
        assertThat(d.status()).isEqualTo(DayStatus.PRESENT);
        assertThat(d.firstIn()).isEqualTo(MON.atTime(8, 55));
        assertThat(d.lastOut()).isEqualTo(MON.atTime(18, 5));
        assertThat(d.workedMinutes()).isEqualTo(550);
        assertThat(d.lateMinutes()).isZero();
        assertThat(d.earlyMinutes()).isZero();
        assertThat(d.overtimeMinutes()).isZero();          // 10 min extra is below the 30 min threshold
        assertThat(d.punches()).isEqualTo(4);
    }

    @Test
    void lateIsCountedFromShiftStartOnlyBeyondGrace() {
        assertThat(one(DAY_SHIFT, MON, LATER, MON.atTime(9, 10), MON.atTime(18, 0)).lateMinutes()).isZero();
        assertThat(one(DAY_SHIFT, MON, LATER, MON.atTime(9, 11), MON.atTime(18, 0)).lateMinutes()).isEqualTo(11);
    }

    @Test
    void earlyDepartureAndOvertime() {
        assertThat(one(DAY_SHIFT, MON, LATER, MON.atTime(9, 0), MON.atTime(17, 50)).earlyMinutes()).isZero();
        assertThat(one(DAY_SHIFT, MON, LATER, MON.atTime(9, 0), MON.atTime(17, 30)).earlyMinutes()).isEqualTo(30);
        DayRecord ot = one(DAY_SHIFT, MON, LATER, MON.atTime(8, 30), MON.atTime(19, 30));
        assertThat(ot.overtimeMinutes()).isEqualTo(120);
    }

    @Test
    void halfDayAndMissingPunch() {
        DayRecord half = one(DAY_SHIFT, MON, LATER, MON.atTime(9, 0), MON.atTime(12, 0));
        assertThat(half.status()).isEqualTo(DayStatus.HALF_DAY);
        assertThat(half.earlyMinutes()).isEqualTo(360);

        DayRecord single = one(DAY_SHIFT, MON, LATER, MON.atTime(9, 0));
        assertThat(single.status()).isEqualTo(DayStatus.PRESENT);
        assertThat(single.missingOut()).isTrue();
        assertThat(single.lastOut()).isNull();
    }

    @Test
    void absenceHolidayWeeklyOffAndLeave() {
        LocalDate sun = MON.minusDays(1);
        List<DayRecord> days = AttendanceEngine.days(DAY_SHIFT, null, null, sun, MON.plusDays(2),
                List.of(sun.atTime(10, 0), sun.atTime(14, 0)),
                Map.of(MON.plusDays(1), "Diwali"),
                Map.of(MON.plusDays(2), new AttendanceEngine.LeaveDay("Casual leave", true)), LATER);
        assertThat(days).extracting(DayRecord::status).containsExactly(
                DayStatus.WEEKLY_OFF, DayStatus.ABSENT, DayStatus.HOLIDAY, DayStatus.LEAVE);
        assertThat(days.getFirst().overtimeMinutes()).isEqualTo(240);   // worked on a day off
        assertThat(days.get(2).note()).isEqualTo("Diwali");
        assertThat(days.get(3).paidLeave()).isTrue();
    }

    @Test
    void todayIsNotJudgedBeforeTheShiftCanBeJudged() {
        // 09:05 with no punch yet: still within grace.
        assertThat(one(DAY_SHIFT, MON, MON.atTime(9, 5)).status()).isEqualTo(DayStatus.UPCOMING);
        // 09:30 with no punch: absent so far.
        assertThat(one(DAY_SHIFT, MON, MON.atTime(9, 30)).status()).isEqualTo(DayStatus.ABSENT);
        // In at 9, lunch punch at 13, it is 14:00: not an early departure or half day yet, not a missing punch.
        DayRecord inProgress = one(DAY_SHIFT, MON, MON.atTime(14, 0), MON.atTime(9, 0), MON.atTime(13, 0));
        assertThat(inProgress.status()).isEqualTo(DayStatus.PRESENT);
        assertThat(inProgress.earlyMinutes()).isZero();
        DayRecord onlyIn = one(DAY_SHIFT, MON, MON.atTime(20, 0), MON.atTime(9, 0));
        assertThat(onlyIn.missingOut()).isFalse();
        // Tomorrow is upcoming.
        assertThat(one(DAY_SHIFT, MON.plusDays(1), MON.atTime(20, 0)).status()).isEqualTo(DayStatus.UPCOMING);
    }

    @Test
    void nightShiftPunchesAfterMidnightBelongToTheStartDay() {
        List<DayRecord> days = AttendanceEngine.days(NIGHT_SHIFT, null, null, MON, MON.plusDays(1),
                List.of(MON.atTime(22, 20), MON.plusDays(1).atTime(6, 45), MON.plusDays(1).atTime(21, 55)),
                Map.of(), Map.of(), LATER);
        DayRecord first = days.getFirst();
        assertThat(first.status()).isEqualTo(DayStatus.PRESENT);
        assertThat(first.lateMinutes()).isEqualTo(20);
        assertThat(first.lastOut()).isEqualTo(MON.plusDays(1).atTime(6, 45));
        assertThat(first.workedMinutes()).isEqualTo(505);
        assertThat(first.overtimeMinutes()).isZero();             // 25 min extra, under the threshold
        assertThat(days.get(1).firstIn()).isEqualTo(MON.plusDays(1).atTime(21, 55));
        assertThat(days.get(1).missingOut()).isTrue();
    }

    @Test
    void daysOutsideEmploymentAreSkipped() {
        List<DayRecord> days = AttendanceEngine.days(DAY_SHIFT, MON.plusDays(1), MON.plusDays(2), MON, MON.plusDays(4),
                List.of(), Map.of(), Map.of(), LATER);
        assertThat(days).extracting(DayRecord::date).containsExactly(MON.plusDays(1), MON.plusDays(2));
    }

    @Test
    void summaryCountsDays() {
        LocalDate sun = MON.minusDays(1);
        List<DayRecord> days = AttendanceEngine.days(DAY_SHIFT, null, null, sun, MON.plusDays(5),
                List.of(MON.atTime(9, 30), MON.atTime(19, 0),                       // late 30, ot 0 (30 min extra = threshold) -> 30
                        MON.plusDays(1).atTime(9, 0), MON.plusDays(1).atTime(12, 0), // half day
                        MON.plusDays(3).atTime(9, 0)),                               // missing out
                Map.of(MON.plusDays(4), "Holiday"),
                Map.of(MON.plusDays(5), new AttendanceEngine.LeaveDay("Unpaid", false)), LATER);
        PeriodSummary s = PeriodSummary.of(days);
        assertThat(s.weeklyOffs()).isEqualTo(1);
        assertThat(s.holidays()).isEqualTo(1);
        assertThat(s.workingDays()).isEqualTo(5);          // Mon-Thu + Sat (leave)
        assertThat(s.presentDays()).isEqualTo(2.5);        // Mon, Thu, half of Tue
        assertThat(s.absentDays()).isEqualTo(1.5);         // Wed + half of Tue
        assertThat(s.unpaidLeaveDays()).isEqualTo(1);
        assertThat(s.lateDays()).isEqualTo(1);
        assertThat(s.lateMinutes()).isEqualTo(30);
        assertThat(s.missingPunchDays()).isEqualTo(1);
        assertThat(s.attendancePercent()).isEqualTo(62.5); // 2.5 / 4
    }
}
