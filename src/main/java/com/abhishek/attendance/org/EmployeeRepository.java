package com.abhishek.attendance.org;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class EmployeeRepository {

    public record Filter(String query, Long departmentId, Boolean active, Long managerScope, Long selfScope) {
        public static Filter all() {
            return new Filter(null, null, null, null, null);
        }
    }

    public record Fields(String employeeCode, String deviceUserId, String fullName, String email, String phone,
                         Long departmentId, String designation, Long managerId, Long workScheduleId,
                         BigDecimal monthlySalary, LocalDate joinedOn, LocalDate leftOn, boolean active) {
    }

    private static final String SELECT = """
            SELECT e.id, e.employee_code, e.device_user_id, e.full_name, e.email, e.phone,
                   e.department_id, d.name AS department_name, e.designation,
                   e.manager_id, m.full_name AS manager_name,
                   e.work_schedule_id, s.name AS work_schedule_name,
                   e.monthly_salary, e.joined_on, e.left_on, e.active,
                   e.user_id, u.email AS user_email, u.role AS user_role, e.created_at, e.updated_at
            FROM employee e
            LEFT JOIN department d ON d.id = e.department_id
            LEFT JOIN employee m ON m.id = e.manager_id
            LEFT JOIN work_schedule s ON s.id = e.work_schedule_id
            LEFT JOIN app_user u ON u.id = e.user_id
            """;

    private final JdbcClient jdbc;

    public EmployeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Employee> findById(long id) {
        return jdbc.sql(SELECT + " WHERE e.id = ?").param(id).query(Employee.class).optional();
    }

    public Optional<Employee> findByUserId(long userId) {
        return jdbc.sql(SELECT + " WHERE e.user_id = ?").param(userId).query(Employee.class).optional();
    }

    public List<Employee> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " WHERE e.id IN (:ids) ORDER BY LOWER(e.full_name)").param("ids", ids)
                .query(Employee.class).list();
    }

    /**
     * managerScope: only employees this employee manages directly or as department head (plus themselves).
     * selfScope: only this employee.
     */
    public List<Employee> search(Filter f) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (f.query() != null && !f.query().isBlank()) {
            sql.append(" AND (LOWER(e.full_name) LIKE ? OR LOWER(e.employee_code) LIKE ? OR e.device_user_id = ? OR LOWER(e.designation) LIKE ?)");
            String like = "%" + f.query().trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(f.query().trim());
            args.add(like);
        }
        if (f.departmentId() != null) {
            sql.append(" AND e.department_id = ?");
            args.add(f.departmentId());
        }
        if (f.active() != null) {
            sql.append(" AND e.active = ?");
            args.add(f.active());
        }
        if (f.managerScope() != null) {
            sql.append(" AND (e.id = ? OR e.manager_id = ? OR d.manager_employee_id = ?)");
            args.add(f.managerScope());
            args.add(f.managerScope());
            args.add(f.managerScope());
        }
        if (f.selfScope() != null) {
            sql.append(" AND e.id = ?");
            args.add(f.selfScope());
        }
        sql.append(" ORDER BY LOWER(e.full_name)");
        return jdbc.sql(sql.toString()).params(args).query(Employee.class).list();
    }

    public long insert(Fields f) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO employee (employee_code, device_user_id, full_name, email, phone, department_id,
                                              designation, manager_id, work_schedule_id, monthly_salary, joined_on,
                                              left_on, active)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(f.employeeCode(), f.deviceUserId(), f.fullName(), f.email(), f.phone(), f.departmentId(),
                        f.designation(), f.managerId(), f.workScheduleId(), f.monthlySalary(), f.joinedOn(),
                        f.leftOn(), f.active())
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void update(long id, Fields f) {
        jdbc.sql("""
                        UPDATE employee SET employee_code = ?, device_user_id = ?, full_name = ?, email = ?, phone = ?,
                               department_id = ?, designation = ?, manager_id = ?, work_schedule_id = ?,
                               monthly_salary = ?, joined_on = ?, left_on = ?, active = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?""")
                .params(f.employeeCode(), f.deviceUserId(), f.fullName(), f.email(), f.phone(), f.departmentId(),
                        f.designation(), f.managerId(), f.workScheduleId(), f.monthlySalary(), f.joinedOn(),
                        f.leftOn(), f.active(), id)
                .update();
    }

    public void linkUser(long employeeId, Long userId) {
        jdbc.sql("UPDATE employee SET user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
                .params(userId, employeeId).update();
    }

    /** Device user numbers that have punches but no employee profile, with the name the device reported. */
    public List<UnmappedDeviceUser> unmappedDeviceUsers() {
        return jdbc.sql("""
                SELECT r.employee_id AS device_user_id, MAX(r.employee_name) AS device_name,
                       COUNT(*) AS punches, MAX(r.check_time) AS last_punch
                FROM attendance_record r
                WHERE NOT EXISTS (SELECT 1 FROM employee e WHERE e.device_user_id = r.employee_id)
                GROUP BY r.employee_id
                ORDER BY MAX(r.check_time) DESC""").query(UnmappedDeviceUser.class).list();
    }

    public record UnmappedDeviceUser(String deviceUserId, String deviceName, long punches,
                                     java.time.LocalDateTime lastPunch) {
    }

    /** True when {@code managerId} manages {@code employeeId} directly or as head of their department. */
    public boolean isManagedBy(long employeeId, long managerId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM employee e LEFT JOIN department d ON d.id = e.department_id
                WHERE e.id = ? AND (e.manager_id = ? OR d.manager_employee_id = ?)""")
                .params(employeeId, managerId, managerId).query(Integer.class).single() > 0;
    }

    /** Walks up the manager chain from {@code managerId} to detect a cycle with {@code employeeId}. */
    public boolean wouldCreateCycle(long employeeId, long managerId) {
        return jdbc.sql("""
                WITH RECURSIVE chain(id) AS (
                    SELECT ?::BIGINT
                    UNION
                    SELECT e.manager_id FROM employee e JOIN chain c ON e.id = c.id WHERE e.manager_id IS NOT NULL
                )
                SELECT COUNT(*) FROM chain WHERE id = ?""")
                .params(managerId, employeeId).query(Integer.class).single() > 0;
    }
}
