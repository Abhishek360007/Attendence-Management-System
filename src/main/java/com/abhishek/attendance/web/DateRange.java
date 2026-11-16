package com.abhishek.attendance.web;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

record DateRange(LocalDate from, LocalDate to) {

    static final int MAX_DAYS = 366;

    static DateRange of(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate start = from != null ? from : (to != null ? to : today);
        LocalDate end = to != null ? to : (from != null ? from : today);
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new IllegalArgumentException("Date range must not exceed " + MAX_DAYS + " days");
        }
        return new DateRange(start, end);
    }
}
