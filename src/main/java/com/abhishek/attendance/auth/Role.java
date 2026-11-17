package com.abhishek.attendance.auth;

/**
 * ADMIN: everything, including user accounts and settings.
 * HR: employees, schedules, holidays, leave, corrections, payroll and reports for everyone.
 * MANAGER: approves leave and corrections for their team and sees team reports.
 * EMPLOYEE: own attendance, leave applications and correction requests.
 */
public enum Role {
    ADMIN, HR, MANAGER, EMPLOYEE
}
