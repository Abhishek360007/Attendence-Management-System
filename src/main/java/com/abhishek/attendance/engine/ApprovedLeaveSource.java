package com.abhishek.attendance.engine;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/** Supplies approved leave to the attendance calculation (implemented by the leave module). */
public interface ApprovedLeaveSource {

    /** employee id -> date -> leave, for approved leave overlapping the range. */
    Map<Long, Map<LocalDate, AttendanceEngine.LeaveDay>> approvedLeaves(Collection<Long> employeeIds,
                                                                       LocalDate from, LocalDate to);
}
