package com.abhishek.attendance.engine;

public enum DayStatus {
    /** Worked the day. */
    PRESENT,
    /** Worked, but fewer minutes than the schedule's half-day threshold. Counts as half present, half absent. */
    HALF_DAY,
    /** A working day with no punches and no approved leave. */
    ABSENT,
    /** Approved leave. */
    LEAVE,
    /** Company holiday. */
    HOLIDAY,
    /** Weekly day off in the employee's schedule. */
    WEEKLY_OFF,
    /** A future day, or today before the shift has started. */
    UPCOMING
}
