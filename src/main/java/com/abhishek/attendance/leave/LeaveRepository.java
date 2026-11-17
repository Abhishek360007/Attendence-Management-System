package com.abhishek.attendance.leave;

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
public class LeaveRepository {

    public record LeaveType(Long id, String name, boolean paid, BigDecimal annualQuota, boolean active) {
    }

    public record Filter(Collection<Long> employeeIds, String status, LocalDate from, LocalDate to, Integer limit) {
    }

    private static final String SELECT = """
            SELECT r.id, r.employee_id, e.full_name AS employee_name, e.employee_code, d.name AS department_name,
                   r.leave_type_id, t.name AS leave_type_name, t.paid, r.start_date, r.end_date, r.days, r.reason,
                   r.status, r.requested_by, rq.display_name AS requested_by_name, r.decided_by,
                   dc.display_name AS decided_by_name, r.decided_at, r.decision_note, r.created_at,
                   NULL::BOOLEAN AS can_decide, NULL::BOOLEAN AS can_cancel
            FROM leave_request r
            JOIN employee e ON e.id = r.employee_id
            LEFT JOIN department d ON d.id = e.department_id
            JOIN leave_type t ON t.id = r.leave_type_id
            LEFT JOIN app_user rq ON rq.id = r.requested_by
            LEFT JOIN app_user dc ON dc.id = r.decided_by
            """;

    private final JdbcClient jdbc;

    public LeaveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- types

    public List<LeaveType> types() {
        return jdbc.sql("SELECT id, name, paid, annual_quota, active FROM leave_type ORDER BY active DESC, LOWER(name)")
                .query(LeaveType.class).list();
    }

    public Optional<LeaveType> type(long id) {
        return jdbc.sql("SELECT id, name, paid, annual_quota, active FROM leave_type WHERE id = ?").param(id)
                .query(LeaveType.class).optional();
    }

    public long insertType(String name, boolean paid, BigDecimal quota) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO leave_type (name, paid, annual_quota) VALUES (?, ?, ?)").params(name, paid, quota)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void updateType(long id, String name, boolean paid, BigDecimal quota, boolean active) {
        jdbc.sql("UPDATE leave_type SET name = ?, paid = ?, annual_quota = ?, active = ? WHERE id = ?")
                .params(name, paid, quota, active, id).update();
    }

    // ---------------------------------------------------------------- allocations

    public Optional<BigDecimal> allocation(long employeeId, long typeId, int year) {
        return jdbc.sql("SELECT days FROM leave_allocation WHERE employee_id = ? AND leave_type_id = ? AND year = ?")
                .params(employeeId, typeId, year).query(BigDecimal.class).optional();
    }

    public void setAllocation(long employeeId, long typeId, int year, BigDecimal days) {
        if (days == null) {
            jdbc.sql("DELETE FROM leave_allocation WHERE employee_id = ? AND leave_type_id = ? AND year = ?")
                    .params(employeeId, typeId, year).update();
        } else {
            jdbc.sql("""
                    INSERT INTO leave_allocation (employee_id, leave_type_id, year, days) VALUES (?, ?, ?, ?)
                    ON CONFLICT (employee_id, leave_type_id, year) DO UPDATE SET days = EXCLUDED.days""")
                    .params(employeeId, typeId, year, days).update();
        }
    }

    /** Days of a type in a year with the given statuses. */
    public BigDecimal daysTaken(long employeeId, long typeId, int year, Collection<String> statuses, Long excludeId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(days), 0) FROM leave_request
                        WHERE employee_id = :e AND leave_type_id = :t AND EXTRACT(YEAR FROM start_date) = :y
                          AND status IN (:s) AND id <> :x""")
                .param("e", employeeId).param("t", typeId).param("y", year).param("s", statuses)
                .param("x", excludeId == null ? -1L : excludeId)
                .query(BigDecimal.class).single();
    }

    // ---------------------------------------------------------------- requests

    public Optional<LeaveRequest> find(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = ?").param(id).query(LeaveRequest.class).optional();
    }

    public List<LeaveRequest> search(Filter f) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (f.employeeIds() != null) {
            if (f.employeeIds().isEmpty()) return List.of();
            sql.append(" AND r.employee_id IN (")
                    .append(String.join(",", f.employeeIds().stream().map(x -> "?").toList())).append(")");
            args.addAll(f.employeeIds());
        }
        if (f.status() != null && !f.status().isBlank()) {
            sql.append(" AND r.status = ?");
            args.add(f.status());
        }
        if (f.from() != null) {
            sql.append(" AND r.end_date >= ?");
            args.add(f.from());
        }
        if (f.to() != null) {
            sql.append(" AND r.start_date <= ?");
            args.add(f.to());
        }
        sql.append(" ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END, r.start_date DESC, r.id DESC LIMIT ?");
        args.add(f.limit() == null ? 500 : f.limit());
        return jdbc.sql(sql.toString()).params(args).query(LeaveRequest.class).list();
    }

    public boolean overlaps(long employeeId, LocalDate from, LocalDate to, Long excludeId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM leave_request
                        WHERE employee_id = ? AND status IN ('PENDING', 'APPROVED') AND start_date <= ? AND end_date >= ?
                          AND id <> ?""")
                .params(employeeId, to, from, excludeId == null ? -1L : excludeId).query(Integer.class).single() > 0;
    }

    public long insert(long employeeId, long typeId, LocalDate from, LocalDate to, BigDecimal days, String reason,
                       Long requestedBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO leave_request (employee_id, leave_type_id, start_date, end_date, days, reason, requested_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(employeeId, typeId, from, to, days, reason, requestedBy).update(keys, "id");
        return keys.getKey().longValue();
    }

    /** Changes status only if it is still {@code expected}; returns false when someone else got there first. */
    public boolean decide(long id, String expected, String status, Long decidedBy, String note) {
        return jdbc.sql("""
                        UPDATE leave_request SET status = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP, decision_note = ?
                        WHERE id = ? AND status = ?""")
                .params(status, decidedBy, note, id, expected).update() == 1;
    }

    /** Approved requests overlapping the range for these employees. */
    public List<LeaveRequest> approvedOverlapping(Collection<Long> employeeIds, LocalDate from, LocalDate to) {
        if (employeeIds.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " WHERE r.status = 'APPROVED' AND r.employee_id IN (:ids) AND r.start_date <= :to AND r.end_date >= :from")
                .param("ids", employeeIds).param("from", from).param("to", to).query(LeaveRequest.class).list();
    }
}
