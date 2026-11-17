package com.abhishek.attendance.notify;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-app notifications, mirrored to email when configured. Listens for leave and correction events after the
 * change is committed, and is used by the scheduled jobs.
 */
@Service
public class NotificationService {

    public record Notification(long id, String kind, String title, String body, String link, LocalDateTime readAt,
                               LocalDateTime createdAt) {
    }

    public record Recipient(long userId, String email, String displayName) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm");

    private final JdbcClient jdbc;
    private final MailService mail;

    public NotificationService(JdbcClient jdbc, MailService mail) {
        this.jdbc = jdbc;
        this.mail = mail;
    }

    // ---------------------------------------------------------------- inbox

    public List<Notification> inbox(long userId, int limit) {
        return jdbc.sql("SELECT id, kind, title, body, link, read_at, created_at FROM notification WHERE user_id = ? ORDER BY id DESC LIMIT ?")
                .params(userId, limit).query(Notification.class).list();
    }

    public int unread(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM notification WHERE user_id = ? AND read_at IS NULL").param(userId)
                .query(Integer.class).single();
    }

    public void markRead(long userId, Long id) {
        if (id == null) {
            jdbc.sql("UPDATE notification SET read_at = CURRENT_TIMESTAMP WHERE user_id = ? AND read_at IS NULL").param(userId).update();
        } else {
            jdbc.sql("UPDATE notification SET read_at = CURRENT_TIMESTAMP WHERE user_id = ? AND id = ? AND read_at IS NULL")
                    .params(userId, id).update();
        }
    }

    // ---------------------------------------------------------------- sending

    /** Stores an in-app notification for each recipient and emails them (one email per recipient). */
    public void notify(Iterable<Recipient> recipients, String kind, String title, String body, String link) {
        for (Recipient r : recipients) {
            jdbc.sql("INSERT INTO notification (user_id, kind, title, body, link) VALUES (?, ?, ?, ?, ?)")
                    .params(r.userId(), kind, title, body, link).update();
            String url = mail.link(link);
            mail.send(List.of(r.email()), title, "Hello " + r.displayName() + ",\n\n" + body
                    + (url == null ? "" : "\n\nOpen: " + url) + "\n\n-- Attendance system");
        }
    }

    /** The sign-in account linked to an employee, if any and active. */
    public Optional<Recipient> userOfEmployee(long employeeId) {
        return jdbc.sql("""
                        SELECT u.id AS user_id, u.email, u.display_name FROM employee e JOIN app_user u ON u.id = e.user_id
                        WHERE e.id = ? AND u.active""")
                .param(employeeId).query(Recipient.class).optional();
    }

    /** Active HR and admin accounts. */
    public List<Recipient> hrUsers() {
        return jdbc.sql("SELECT id AS user_id, email, display_name FROM app_user WHERE active AND role IN ('HR', 'ADMIN') ORDER BY id")
                .query(Recipient.class).list();
    }

    /** Who approves this employee's requests: direct manager and department head; HR when there is neither. */
    public List<Recipient> approversOf(long employeeId) {
        List<Recipient> managers = jdbc.sql("""
                        SELECT DISTINCT u.id AS user_id, u.email, u.display_name
                        FROM employee e
                        LEFT JOIN department d ON d.id = e.department_id
                        JOIN employee m ON m.id = e.manager_id OR m.id = d.manager_employee_id
                        JOIN app_user u ON u.id = m.user_id
                        WHERE e.id = ? AND u.active AND m.id <> e.id""")
                .param(employeeId).query(Recipient.class).list();
        return managers.isEmpty() ? hrUsers() : managers;
    }

    // ---------------------------------------------------------------- events

    @Async
    @TransactionalEventListener
    public void on(Events.LeaveRequested e) {
        leave(e.requestId()).ifPresent(r -> notify(approversOf(e.employeeId()), "LEAVE_REQUEST",
                "Leave request from " + r.get("employee_name"),
                "%s applied for %s from %s to %s (%s working days).%s".formatted(r.get("employee_name"), r.get("type_name"),
                        DATE.format(((java.sql.Date) r.get("start_date")).toLocalDate()),
                        DATE.format(((java.sql.Date) r.get("end_date")).toLocalDate()),
                        stripZeros(r.get("days")), reason(r.get("reason"))),
                "/leave?tab=approvals"));
    }

    @Async
    @TransactionalEventListener
    public void on(Events.LeaveDecided e) {
        if ("CANCELLED".equals(e.status())) return;
        Optional<Recipient> to = userOfEmployee(e.employeeId());
        Optional<Map<String, Object>> r = leave(e.requestId());
        if (to.isEmpty() || r.isEmpty()) return;
        String verb = "APPROVED".equals(e.status()) ? "approved" : "rejected";
        notify(List.of(to.get()), "LEAVE_" + e.status(), "Your leave was " + verb,
                "Your %s from %s to %s was %s by %s.%s".formatted(r.get().get("type_name"),
                        DATE.format(((java.sql.Date) r.get().get("start_date")).toLocalDate()),
                        DATE.format(((java.sql.Date) r.get().get("end_date")).toLocalDate()), verb,
                        r.get().get("decided_by_name"), note(r.get().get("decision_note"))),
                "/leave");
    }

    @Async
    @TransactionalEventListener
    public void on(Events.CorrectionRequested e) {
        correction(e.correctionId()).ifPresent(c -> notify(approversOf(e.employeeId()), "CORRECTION_REQUEST",
                "Punch correction request from " + c.get("employee_name"),
                "%s asked to %s.%s".formatted(c.get("employee_name"), describe(c), reason(c.get("reason"))),
                "/corrections?tab=approvals"));
    }

    @Async
    @TransactionalEventListener
    public void on(Events.CorrectionDecided e) {
        if ("CANCELLED".equals(e.status())) return;
        Optional<Recipient> to = userOfEmployee(e.employeeId());
        Optional<Map<String, Object>> c = correction(e.correctionId());
        if (to.isEmpty() || c.isEmpty()) return;
        String verb = "APPROVED".equals(e.status()) ? "approved" : "rejected";
        notify(List.of(to.get()), "CORRECTION_" + e.status(), "Your punch correction was " + verb,
                "Your request to %s was %s by %s.%s".formatted(describe(c.get()), verb, c.get().get("decided_by_name"),
                        note(c.get().get("decision_note"))),
                "/corrections");
    }

    private Optional<Map<String, Object>> leave(long id) {
        return jdbc.sql("""
                        SELECT e.full_name AS employee_name, t.name AS type_name, r.start_date, r.end_date, r.days, r.reason,
                               r.decision_note, u.display_name AS decided_by_name
                        FROM leave_request r JOIN employee e ON e.id = r.employee_id JOIN leave_type t ON t.id = r.leave_type_id
                        LEFT JOIN app_user u ON u.id = r.decided_by WHERE r.id = ?""")
                .param(id).query().listOfRows().stream().findFirst();
    }

    private Optional<Map<String, Object>> correction(long id) {
        return jdbc.sql("""
                        SELECT e.full_name AS employee_name, c.action, c.original_time, c.requested_time, c.reason,
                               c.decision_note, u.display_name AS decided_by_name
                        FROM punch_correction c JOIN employee e ON e.id = c.employee_id
                        LEFT JOIN app_user u ON u.id = c.decided_by WHERE c.id = ?""")
                .param(id).query().listOfRows().stream().findFirst();
    }

    private static String describe(Map<String, Object> c) {
        String original = c.get("original_time") == null ? null : DATE_TIME.format(((java.sql.Timestamp) c.get("original_time")).toLocalDateTime());
        String requested = c.get("requested_time") == null ? null : DATE_TIME.format(((java.sql.Timestamp) c.get("requested_time")).toLocalDateTime());
        return switch ((String) c.get("action")) {
            case "ADD" -> "add a punch at " + requested;
            case "MODIFY" -> "change the punch at " + original + " to " + requested;
            default -> "remove the punch at " + original;
        };
    }

    private static String reason(Object r) {
        return r == null ? "" : "\nReason: " + r;
    }

    private static String note(Object n) {
        return n == null ? "" : "\nNote: " + n;
    }

    private static String stripZeros(Object n) {
        return n instanceof java.math.BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(n);
    }

    /** Deduplicates recipients by user id, keeping order. */
    public static List<Recipient> distinct(List<Recipient> list) {
        Set<Long> seen = new LinkedHashSet<>();
        List<Recipient> out = new ArrayList<>();
        for (Recipient r : list) if (seen.add(r.userId())) out.add(r);
        return out;
    }
}
