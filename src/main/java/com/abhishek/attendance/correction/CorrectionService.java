package com.abhishek.attendance.correction;

import com.abhishek.attendance.audit.AuditService;
import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.auth.Role;
import com.abhishek.attendance.notify.Events;
import com.abhishek.attendance.org.AccessPolicy;
import com.abhishek.attendance.org.Employee;
import com.abhishek.attendance.org.EmployeeRepository;
import com.abhishek.attendance.org.EmployeeService;
import com.abhishek.attendance.settings.SettingsService;
import com.abhishek.attendance.web.ConflictException;
import com.abhishek.attendance.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Punch corrections. Nobody edits punches directly: every change is a request that someone else approves, and
 * approving it voids the original punch (kept for the record) and adds a MANUAL punch with the corrected time.
 */
@Service
public class CorrectionService {

    public enum Action { ADD, MODIFY, REMOVE }

    public record Correction(Long id, Long employeeId, String employeeName, String employeeCode, String action,
                             Long attendanceRecordId, LocalDateTime originalTime, LocalDateTime requestedTime,
                             String reason, String status, Long requestedBy, String requestedByName, Long decidedBy,
                             String decidedByName, LocalDateTime decidedAt, String decisionNote, Long appliedRecordId,
                             LocalDateTime createdAt, Boolean canDecide, Boolean canCancel) {
        Correction with(boolean decide, boolean cancel) {
            return new Correction(id, employeeId, employeeName, employeeCode, action, attendanceRecordId, originalTime,
                    requestedTime, reason, status, requestedBy, requestedByName, decidedBy, decidedByName, decidedAt,
                    decisionNote, appliedRecordId, createdAt, decide, cancel);
        }
    }

    /** A stored punch as an employee sees it when choosing one to correct. */
    public record Punch(long id, LocalDateTime checkTime, String source, boolean voided, Long correctionId) {
    }

    private static final String SELECT = """
            SELECT c.id, c.employee_id, e.full_name AS employee_name, e.employee_code, c.action, c.attendance_record_id,
                   c.original_time, c.requested_time, c.reason, c.status, c.requested_by,
                   rq.display_name AS requested_by_name, c.decided_by, dc.display_name AS decided_by_name, c.decided_at,
                   c.decision_note, c.applied_record_id, c.created_at,
                   NULL::BOOLEAN AS can_decide, NULL::BOOLEAN AS can_cancel
            FROM punch_correction c
            JOIN employee e ON e.id = c.employee_id
            LEFT JOIN app_user rq ON rq.id = c.requested_by
            LEFT JOIN app_user dc ON dc.id = c.decided_by
            """;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcClient jdbc;
    private final EmployeeService employees;
    private final AccessPolicy access;
    private final SettingsService settings;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public CorrectionService(JdbcClient jdbc, EmployeeService employees, AccessPolicy access, SettingsService settings,
                             AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.access = access;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
    }

    /** An employee's stored punches, including voided ones, for the correction form and the audit view. */
    public List<Punch> punches(AppUser user, long employeeId, LocalDate from, LocalDate to) {
        access.checkCanView(user, employeeId);
        Employee e = employees.get(employeeId);
        if (e.deviceUserId() == null) return List.of();
        return jdbc.sql("""
                        SELECT id, check_time, source, voided, correction_id FROM attendance_record
                        WHERE employee_id = ? AND check_time >= ? AND check_time < ? ORDER BY check_time, id""")
                .params(e.deviceUserId(), Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.plusDays(1).atStartOfDay()))
                .query(Punch.class).list();
    }

    @Transactional
    public Correction request(AppUser user, Long employeeId, Action action, Long recordId, LocalDateTime requestedTime,
                              String reason, boolean approveNow) {
        Employee e = employeeId == null
                ? access.employeeOf(user).orElseThrow(() -> new ConflictException("Your account is not linked to an employee profile."))
                : employees.get(employeeId);
        boolean own = access.employeeOf(user).map(me -> me.id().equals(e.id())).orElse(false);
        if (!own && !access.canApprove(user, e.id())) {
            throw new AccessDeniedException("You can only request corrections for yourself or your team");
        }
        if (e.deviceUserId() == null) {
            throw new ConflictException(e.fullName() + " has no device ID, so there are no punches to correct.");
        }
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required.");
        LocalDateTime now = settings.now();
        LocalDateTime original = null;
        switch (action) {
            case ADD -> {
                if (recordId != null) throw new IllegalArgumentException("Adding a punch does not refer to an existing one.");
                requireTime(requestedTime, now);
            }
            case MODIFY, REMOVE -> {
                if (recordId == null) throw new IllegalArgumentException("Choose the punch to " + action.name().toLowerCase() + ".");
                Punch p = punchOf(e, recordId);
                if (p.voided()) throw new ConflictException("That punch was already replaced or removed.");
                original = p.checkTime();
                if (action == Action.MODIFY) {
                    requireTime(requestedTime, now);
                    if (requestedTime.equals(original)) throw new IllegalArgumentException("The new time is the same as the current one.");
                } else if (requestedTime != null) {
                    throw new IllegalArgumentException("Removing a punch takes no new time.");
                }
                int open = jdbc.sql("SELECT COUNT(*) FROM punch_correction WHERE attendance_record_id = ? AND status = 'PENDING'")
                        .param(recordId).query(Integer.class).single();
                if (open > 0) throw new ConflictException("There is already a pending correction for that punch.");
            }
        }
        if (approveNow && !access.canApprove(user, e.id())) {
            throw new AccessDeniedException("Only a manager or HR can approve corrections, and never their own");
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO punch_correction (employee_id, action, attendance_record_id, original_time, requested_time,
                                                      reason, requested_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(e.id(), action.name(), recordId, original == null ? null : Timestamp.valueOf(original),
                        action == Action.REMOVE ? null : Timestamp.valueOf(requestedTime), reason.trim(), user.id())
                .update(keys, "id");
        long id = keys.getKey().longValue();
        audit.record("CORRECTION_REQUESTED", "correction", id, describe(e.fullName(), action, original, requestedTime, reason.trim()));
        if (approveNow) {
            return decide(user, id, true, "Entered and approved by " + user.displayName());
        }
        events.publishEvent(new Events.CorrectionRequested(id, e.id()));
        return get(user, id);
    }

    @Transactional
    public Correction decide(AppUser user, long id, boolean approve, String note) {
        Correction c = find(id);
        access.checkCanApprove(user, c.employeeId());
        if (!"PENDING".equals(c.status())) {
            throw new ConflictException("This correction was already " + c.status().toLowerCase() + ".");
        }
        Long applied = null;
        if (approve) {
            Employee e = employees.get(c.employeeId());
            if (c.attendanceRecordId() != null) {
                int voided = jdbc.sql("UPDATE attendance_record SET voided = TRUE, correction_id = ? WHERE id = ? AND NOT voided")
                        .params(id, c.attendanceRecordId()).update();
                if (voided == 0) throw new ConflictException("The punch was already changed by another correction.");
            }
            if (c.requestedTime() != null) {
                GeneratedKeyHolder keys = new GeneratedKeyHolder();
                jdbc.sql("""
                                INSERT INTO attendance_record (employee_id, employee_name, check_time, check_type, device_id,
                                                               source_id, source, correction_id)
                                VALUES (?, ?, ?, NULL, NULL, ?, 'MANUAL', ?)""")
                        .params(e.deviceUserId(), e.fullName(), Timestamp.valueOf(c.requestedTime()), -id, id)
                        .update(keys, "id");
                applied = keys.getKey().longValue();
            }
        }
        String status = approve ? "APPROVED" : "REJECTED";
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        int changed = jdbc.sql("""
                        UPDATE punch_correction SET status = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP,
                               decision_note = ?, applied_record_id = ?
                        WHERE id = ? AND status = 'PENDING'""")
                .params(status, user.id(), cleanNote, applied, id).update();
        if (changed == 0) throw new ConflictException("This correction was decided by someone else just now.");
        audit.record(approve ? "CORRECTION_APPROVED" : "CORRECTION_REJECTED", "correction", id,
                describe(c.employeeName(), Action.valueOf(c.action()), c.originalTime(), c.requestedTime(), c.reason())
                        + (cleanNote == null ? "" : " | note: " + cleanNote));
        if (approve) {
            audit.record("PUNCH_" + c.action(), "punch", c.attendanceRecordId() != null ? c.attendanceRecordId() : applied,
                    "correction #" + id + ": " + describe(c.employeeName(), Action.valueOf(c.action()), c.originalTime(), c.requestedTime(), null));
        }
        events.publishEvent(new Events.CorrectionDecided(id, c.employeeId(), status));
        return get(user, id);
    }

    @Transactional
    public Correction cancel(AppUser user, long id) {
        Correction c = get(user, id);
        if (!Boolean.TRUE.equals(c.canCancel())) throw new ConflictException("Only a pending request can be cancelled, by the person who made it.");
        int changed = jdbc.sql("UPDATE punch_correction SET status = 'CANCELLED', decided_by = ?, decided_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PENDING'")
                .params(user.id(), id).update();
        if (changed == 0) throw new ConflictException("This request changed just now; reload and try again.");
        audit.record("CORRECTION_CANCELLED", "correction", id, c.employeeName());
        return get(user, id);
    }

    public Correction get(AppUser user, long id) {
        Correction c = find(id);
        access.checkCanView(user, c.employeeId());
        return withPermissions(user, c);
    }

    public List<Correction> list(AppUser user, String scope, String status) {
        Collection<Long> ids;
        String s = status;
        switch (scope == null ? "mine" : scope) {
            case "mine" -> ids = access.employeeOf(user).map(e -> List.of(e.id())).orElse(List.of());
            case "approvals" -> {
                ids = user.hasRole(Role.ADMIN, Role.HR) ? null : team(user);
                if (s == null) s = "PENDING";
            }
            case "all" -> ids = AccessPolicy.isStaff(user) ? null : team(user);
            default -> throw new IllegalArgumentException("scope must be mine, approvals or all");
        }
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (ids != null) {
            if (ids.isEmpty()) return List.of();
            sql.append(" AND c.employee_id IN (").append(String.join(",", ids.stream().map(x -> "?").toList())).append(")");
            args.addAll(ids);
        }
        if (s != null && !s.isBlank()) {
            sql.append(" AND c.status = ?");
            args.add(s);
        }
        sql.append(" ORDER BY CASE c.status WHEN 'PENDING' THEN 0 ELSE 1 END, c.id DESC LIMIT 500");
        return jdbc.sql(sql.toString()).params(args).query(Correction.class).list().stream()
                .filter(c -> !"approvals".equals(scope) || access.canApprove(user, c.employeeId()))
                .map(c -> withPermissions(user, c)).toList();
    }

    private Correction withPermissions(AppUser user, Correction c) {
        boolean pending = "PENDING".equals(c.status());
        return c.with(pending && access.canApprove(user, c.employeeId()),
                pending && c.requestedBy() != null && c.requestedBy().equals(user.id()));
    }

    private List<Long> team(AppUser user) {
        return employees.search(access.scope(user, new EmployeeRepository.Filter(null, null, null, null, null)))
                .stream().map(Employee::id).toList();
    }

    private Correction find(long id) {
        return jdbc.sql(SELECT + " WHERE c.id = ?").param(id).query(Correction.class).optional()
                .orElseThrow(() -> new NotFoundException("Correction", id));
    }

    private Punch punchOf(Employee e, long recordId) {
        return jdbc.sql("SELECT id, check_time, source, voided, correction_id FROM attendance_record WHERE id = ? AND employee_id = ?")
                .params(recordId, e.deviceUserId()).query(Punch.class).optional()
                .orElseThrow(() -> new NotFoundException("Punch", recordId + " of " + e.fullName()));
    }

    private static void requireTime(LocalDateTime t, LocalDateTime now) {
        if (t == null) throw new IllegalArgumentException("The punch time is required.");
        if (t.isAfter(now.plusMinutes(5))) throw new IllegalArgumentException("A punch cannot be in the future.");
        if (t.isBefore(now.minusDays(400))) throw new IllegalArgumentException("That date is too far in the past.");
    }

    private static String describe(String name, Action a, LocalDateTime original, LocalDateTime requested, String reason) {
        String what = switch (a) {
            case ADD -> "add punch at " + FMT.format(requested);
            case MODIFY -> "change punch " + FMT.format(original) + " to " + FMT.format(requested);
            case REMOVE -> "remove punch at " + FMT.format(original);
        };
        return name + ": " + what + (reason == null ? "" : " (reason: " + reason + ")");
    }
}
