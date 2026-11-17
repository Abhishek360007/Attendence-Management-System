package com.abhishek.attendance.audit;

import com.abhishek.attendance.auth.AppUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only record of changes: who did what, to which record, and when.
 */
@Service
public class AuditService {

    public record Entry(long id, LocalDateTime at, Long actorId, String actorName, String action,
                        String entityType, String entityId, String details) {
    }

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records an action by the signed-in user (or "system" for scheduled jobs). */
    public void record(String action, String entityType, Object entityId, String details) {
        Long actorId = null;
        String actorName = "system";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUser user) {
            actorId = user.id();
            actorName = user.displayName() + " <" + user.email() + ">";
        } else if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            actorName = auth.getName();
        }
        jdbc.sql("""
                        INSERT INTO audit_log (actor_id, actor_name, action, entity_type, entity_id, details)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .params(actorId, actorName, action, entityType, entityId == null ? null : String.valueOf(entityId), details)
                .update();
    }

    public List<Entry> search(String entityType, String entityId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND entity_type = ?");
            args.add(entityType);
        }
        if (entityId != null && !entityId.isBlank()) {
            sql.append(" AND entity_id = ?");
            args.add(entityId);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.sql(sql.toString()).params(args).query(Entry.class).list();
    }
}
