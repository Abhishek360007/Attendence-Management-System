package com.abhishek.attendance.org;

public record Department(
        Long id,
        String name,
        Long workScheduleId,
        String workScheduleName,
        Long managerEmployeeId,
        String managerName,
        int employeeCount) {
}
