package com.abhishek.attendance.org;

import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.auth.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Who may see or act on which employee.
 * ADMIN and HR: everyone. MANAGER: themselves, their direct reports and the departments they head.
 * EMPLOYEE: only themselves. API-key clients (user == null) have read access like HR, where an endpoint allows them.
 */
@Component
public class AccessPolicy {

    private final EmployeeRepository employees;

    public AccessPolicy(EmployeeRepository employees) {
        this.employees = employees;
    }

    public static boolean isStaff(AppUser user) {
        return user == null || user.hasRole(Role.ADMIN, Role.HR);
    }

    public Optional<Employee> employeeOf(AppUser user) {
        return user == null ? Optional.empty() : employees.findByUserId(user.id());
    }

    /** Narrows a search to what the user may see. */
    public EmployeeRepository.Filter scope(AppUser user, EmployeeRepository.Filter f) {
        if (isStaff(user)) {
            return f;
        }
        long me = employeeOf(user).map(Employee::id).orElse(-1L);
        if (user.role() == Role.MANAGER) {
            return new EmployeeRepository.Filter(f.query(), f.departmentId(), f.active(), me, null);
        }
        return new EmployeeRepository.Filter(f.query(), f.departmentId(), f.active(), null, me);
    }

    public boolean canView(AppUser user, long employeeId) {
        if (isStaff(user)) {
            return true;
        }
        Optional<Employee> me = employeeOf(user);
        if (me.isEmpty()) {
            return false;
        }
        if (me.get().id() == employeeId) {
            return true;
        }
        return user.role() == Role.MANAGER && employees.isManagedBy(employeeId, me.get().id());
    }

    public void checkCanView(AppUser user, long employeeId) {
        if (!canView(user, employeeId)) {
            throw new AccessDeniedException("Not allowed to view this employee");
        }
    }

    /** Approving leave or corrections: HR/admin for anyone, a manager for their team but never for themselves. */
    public boolean canApprove(AppUser user, long employeeId) {
        if (user == null) {
            return false;
        }
        if (user.hasRole(Role.ADMIN, Role.HR)) {
            return employeeOf(user).map(e -> e.id() != employeeId).orElse(true);
        }
        if (user.role() != Role.MANAGER) {
            return false;
        }
        return employeeOf(user).map(me -> me.id() != employeeId && employees.isManagedBy(employeeId, me.id()))
                .orElse(false);
    }

    public void checkCanApprove(AppUser user, long employeeId) {
        if (!canApprove(user, employeeId)) {
            throw new AccessDeniedException("Not allowed to approve requests for this employee");
        }
    }
}
