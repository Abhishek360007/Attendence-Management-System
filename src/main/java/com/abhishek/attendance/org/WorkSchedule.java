package com.abhishek.attendance.org;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Working hours, grace periods and weekly days off. A schedule whose end is earlier than its start is a night
 * shift ending the next day.
 */
public record WorkSchedule(
        Long id,
        String name,
        LocalTime startTime,
        LocalTime endTime,
        int lateGraceMinutes,
        int earlyGraceMinutes,
        int overtimeMinMinutes,
        int halfDayMinutes,
        String weeklyOffs,
        boolean isDefault) {

    @JsonIgnore
    public Set<DayOfWeek> weeklyOffDays() {
        return parseOffs(weeklyOffs);
    }

    @JsonIgnore
    public boolean overnight() {
        return !endTime.isAfter(startTime);
    }

    /** Scheduled working minutes per day. */
    public long scheduledMinutes() {
        long m = Duration.between(startTime, endTime).toMinutes();
        return m <= 0 ? m + 24 * 60 : m;
    }

    public static Set<DayOfWeek> parseOffs(String value) {
        if (value == null || value.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> DayOfWeek.valueOf(s.toUpperCase()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    public static String formatOffs(Set<DayOfWeek> days) {
        return days.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
    }
}
