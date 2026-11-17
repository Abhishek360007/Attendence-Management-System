package com.abhishek.attendance.org;

import com.abhishek.attendance.audit.AuditService;
import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.auth.Role;
import com.abhishek.attendance.auth.UserService;
import com.abhishek.attendance.web.ConflictException;
import com.abhishek.attendance.web.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final UserService users;

    public EmployeeService(EmployeeRepository employees, JdbcClient jdbc, AuditService audit, UserService users) {
        this.employees = employees;
        this.jdbc = jdbc;
        this.audit = audit;
        this.users = users;
    }

    public Employee get(long id) {
        return employees.findById(id).orElseThrow(() -> new NotFoundException("Employee", id));
    }

    public List<Employee> search(EmployeeRepository.Filter filter) {
        return employees.search(filter);
    }

    @Transactional
    public Employee create(EmployeeRepository.Fields f) {
        EmployeeRepository.Fields clean = normalize(f);
        validate(null, clean);
        long id;
        try {
            id = employees.insert(clean);
        } catch (DuplicateKeyException e) {
            throw duplicate(clean);
        }
        audit.record("EMPLOYEE_CREATED", "employee", id, clean.employeeCode() + " " + clean.fullName()
                + (clean.deviceUserId() != null ? ", device ID " + clean.deviceUserId() : ""));
        return get(id);
    }

    @Transactional
    public Employee update(long id, EmployeeRepository.Fields f) {
        Employee before = get(id);
        EmployeeRepository.Fields clean = normalize(f);
        validate(id, clean);
        try {
            employees.update(id, clean);
        } catch (DuplicateKeyException e) {
            throw duplicate(clean);
        }
        Employee after = get(id);
        String changes = diff(before, after);
        if (!changes.isEmpty()) {
            audit.record(after.active() || before.active() == after.active() ? "EMPLOYEE_UPDATED" : "EMPLOYEE_DEACTIVATED",
                    "employee", id, changes);
        }
        // A deactivated employee can no longer sign in.
        if (before.active() && !after.active() && after.userId() != null) {
            AppUser u = users.get(after.userId());
            if (u.active()) {
                users.update(u.id(), u.displayName(), u.role(), false, systemActor());
            }
        }
        return after;
    }

    /** Creates a sign-in account for an employee, or links an existing one with the same email. */
    @Transactional
    public Employee createLogin(long employeeId, String email, String password, Role role) {
        Employee e = get(employeeId);
        if (e.userId() != null) {
            throw new ConflictException("This employee already has a sign-in account (" + e.userEmail() + ").");
        }
        if (!e.active()) {
            throw new ConflictException("The employee is deactivated.");
        }
        AppUser user = users.create(email, e.fullName(), password, role == null ? Role.EMPLOYEE : role);
        employees.linkUser(employeeId, user.id());
        audit.record("EMPLOYEE_LOGIN_CREATED", "employee", employeeId, user.email() + " as " + user.role());
        return get(employeeId);
    }

    public List<EmployeeRepository.UnmappedDeviceUser> unmappedDeviceUsers() {
        return employees.unmappedDeviceUsers();
    }

    // ---------------------------------------------------------------- departments

    public List<Department> departments() {
        return jdbc.sql("""
                SELECT d.id, d.name, d.work_schedule_id, s.name AS work_schedule_name, d.manager_employee_id,
                       m.full_name AS manager_name,
                       (SELECT COUNT(*) FROM employee e WHERE e.department_id = d.id AND e.active) AS employee_count
                FROM department d
                LEFT JOIN work_schedule s ON s.id = d.work_schedule_id
                LEFT JOIN employee m ON m.id = d.manager_employee_id
                ORDER BY LOWER(d.name)""").query(Department.class).list();
    }

    public Department department(long id) {
        return departments().stream().filter(d -> d.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Department", id));
    }

    @Transactional
    public Department saveDepartment(Long id, String name, Long scheduleId, Long managerEmployeeId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Department name is required.");
        if (managerEmployeeId != null) get(managerEmployeeId);
        try {
            if (id == null) {
                GeneratedKeyHolder keys = new GeneratedKeyHolder();
                jdbc.sql("INSERT INTO department (name, work_schedule_id, manager_employee_id) VALUES (?, ?, ?)")
                        .params(name.trim(), scheduleId, managerEmployeeId).update(keys, "id");
                id = keys.getKey().longValue();
                audit.record("DEPARTMENT_CREATED", "department", id, name.trim());
            } else {
                Department before = department(id);
                jdbc.sql("UPDATE department SET name = ?, work_schedule_id = ?, manager_employee_id = ? WHERE id = ?")
                        .params(name.trim(), scheduleId, managerEmployeeId, id).update();
                audit.record("DEPARTMENT_UPDATED", "department", id,
                        "%s (schedule %s, head %s) -> %s".formatted(before.name(), before.workScheduleName(),
                                before.managerName(), name.trim()));
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A department named " + name.trim() + " already exists.");
        }
        return department(id);
    }

    @Transactional
    public void deleteDepartment(long id) {
        Department d = department(id);
        int members = jdbc.sql("SELECT COUNT(*) FROM employee WHERE department_id = ?").param(id).query(Integer.class).single();
        if (members > 0) {
            throw new ConflictException("Move the " + members + " employee(s) out of " + d.name() + " first.");
        }
        jdbc.sql("DELETE FROM department WHERE id = ?").param(id).update();
        audit.record("DEPARTMENT_DELETED", "department", id, d.name());
    }

    // ---------------------------------------------------------------- helpers

    private void validate(Long id, EmployeeRepository.Fields f) {
        if (f.employeeCode() == null) throw new IllegalArgumentException("Employee ID is required.");
        if (f.fullName() == null) throw new IllegalArgumentException("Name is required.");
        if (f.joinedOn() != null && f.leftOn() != null && f.leftOn().isBefore(f.joinedOn())) {
            throw new IllegalArgumentException("Leaving date cannot be before joining date.");
        }
        if (f.managerId() != null) {
            if (Objects.equals(f.managerId(), id)) throw new IllegalArgumentException("An employee cannot manage themselves.");
            get(f.managerId());
            if (id != null && employees.wouldCreateCycle(id, f.managerId())) {
                throw new IllegalArgumentException("That manager reports (directly or indirectly) to this employee.");
            }
        }
    }

    private static EmployeeRepository.Fields normalize(EmployeeRepository.Fields f) {
        return new EmployeeRepository.Fields(blank(f.employeeCode()), blank(f.deviceUserId()), blank(f.fullName()),
                blank(f.email()), blank(f.phone()), f.departmentId(), blank(f.designation()), f.managerId(),
                f.workScheduleId(), f.monthlySalary(), f.joinedOn(), f.leftOn(), f.active());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ConflictException duplicate(EmployeeRepository.Fields f) {
        String msg = "Another employee already uses employee ID " + f.employeeCode();
        if (f.deviceUserId() != null) msg += " or device ID " + f.deviceUserId();
        return new ConflictException(msg + ".");
    }

    private static String diff(Employee a, Employee b) {
        List<String> out = new ArrayList<>();
        cmp(out, "employee ID", a.employeeCode(), b.employeeCode());
        cmp(out, "device ID", a.deviceUserId(), b.deviceUserId());
        cmp(out, "name", a.fullName(), b.fullName());
        cmp(out, "email", a.email(), b.email());
        cmp(out, "phone", a.phone(), b.phone());
        cmp(out, "department", a.departmentName(), b.departmentName());
        cmp(out, "designation", a.designation(), b.designation());
        cmp(out, "manager", a.managerName(), b.managerName());
        cmp(out, "schedule", a.workScheduleName(), b.workScheduleName());
        cmp(out, "salary", a.monthlySalary() == null ? null : a.monthlySalary().stripTrailingZeros().toPlainString(),
                b.monthlySalary() == null ? null : b.monthlySalary().stripTrailingZeros().toPlainString());
        cmp(out, "joined", a.joinedOn(), b.joinedOn());
        cmp(out, "left", a.leftOn(), b.leftOn());
        cmp(out, "active", a.active(), b.active());
        return String.join("; ", out);
    }

    private static void cmp(List<String> out, String label, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            out.add(label + ": " + (a == null ? "–" : a) + " -> " + (b == null ? "–" : b));
        }
    }

    /** Used when the system itself deactivates a login; never matches a real user id. */
    private static AppUser systemActor() {
        return new AppUser(-1L, "system", "system", "", Role.ADMIN, true, null, null);
    }
}
