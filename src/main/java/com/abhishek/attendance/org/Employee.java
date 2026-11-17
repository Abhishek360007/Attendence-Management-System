package com.abhishek.attendance.org;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An employee profile. {@code deviceUserId} is the user number on the ZKTeco devices and links punches to the
 * profile. Joined columns (department name, manager name, schedule name, login email) are filled for display.
 */
public record Employee(
        Long id,
        String employeeCode,
        String deviceUserId,
        String fullName,
        String email,
        String phone,
        Long departmentId,
        String departmentName,
        String designation,
        Long managerId,
        String managerName,
        Long workScheduleId,
        String workScheduleName,
        BigDecimal monthlySalary,
        LocalDate joinedOn,
        LocalDate leftOn,
        boolean active,
        Long userId,
        String userEmail,
        String userRole,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** The salary is only shown to HR and admins. */
    public Employee withoutSalary() {
        return new Employee(id, employeeCode, deviceUserId, fullName, email, phone, departmentId, departmentName,
                designation, managerId, managerName, workScheduleId, workScheduleName, null, joinedOn, leftOn, active,
                userId, userEmail, userRole, createdAt, updatedAt);
    }
}
