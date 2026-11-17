package com.abhishek.attendance.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record LeaveRequest(
        Long id,
        Long employeeId,
        String employeeName,
        String employeeCode,
        String departmentName,
        Long leaveTypeId,
        String leaveTypeName,
        boolean paid,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal days,
        String reason,
        String status,
        Long requestedBy,
        String requestedByName,
        Long decidedBy,
        String decidedByName,
        LocalDateTime decidedAt,
        String decisionNote,
        LocalDateTime createdAt,
        /** Filled per caller: whether they may approve or reject it. */
        Boolean canDecide,
        /** Filled per caller: whether they may cancel it. */
        Boolean canCancel) {

    public LeaveRequest withPermissions(boolean decide, boolean cancel) {
        return new LeaveRequest(id, employeeId, employeeName, employeeCode, departmentName, leaveTypeId, leaveTypeName,
                paid, startDate, endDate, days, reason, status, requestedBy, requestedByName, decidedBy, decidedByName,
                decidedAt, decisionNote, createdAt, decide, cancel);
    }
}
