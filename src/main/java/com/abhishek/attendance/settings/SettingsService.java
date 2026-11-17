package com.abhishek.attendance.settings;

import com.abhishek.attendance.audit.AuditService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Company-wide settings. Times in the database are office local time, so "today" and "now" must use the
 * company time zone rather than the server's (Render runs in UTC).
 */
@Service
public class SettingsService {

    /** Keys an admin may set, with defaults used when a key is missing. */
    public static final Map<String, String> DEFAULTS = defaults();

    private static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("company_name", "My Company");
        m.put("timezone", "Asia/Kolkata");
        m.put("overtime_rate_multiplier", "1.5");
        m.put("hr_notification_emails", "");
        m.put("notify_absence_time", "11:00");
        m.put("notify_employees_late", "true");
        return m;
    }

    private static final Set<String> PUBLIC_KEYS = Set.of("company_name", "timezone");

    private final JdbcClient jdbc;
    private final AuditService audit;
    // Short-lived: the cloud API and the office-PC agent share the database, so changes must reach both.
    private static final long CACHE_MILLIS = 60_000;
    private record Cached(String value, long loadedAt) {
    }
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SettingsService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public String get(String key) {
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c == null || now - c.loadedAt() > CACHE_MILLIS) {
            String value = jdbc.sql("SELECT value FROM company_setting WHERE key = ?")
                    .param(key).query(String.class).optional().orElse(DEFAULTS.getOrDefault(key, ""));
            c = new Cached(value, now);
            cache.put(key, c);
        }
        return c.value();
    }

    /** Drops cached values (used by tests that change settings directly in the database). */
    public void evictCache() {
        cache.clear();
    }

    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        DEFAULTS.keySet().forEach(k -> out.put(k, get(k)));
        return out;
    }

    public Map<String, String> publicSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        PUBLIC_KEYS.forEach(k -> out.put(k, get(k)));
        out.put("today", today().toString());
        return out;
    }

    @Transactional
    public Map<String, String> update(Map<String, String> changes) {
        StringBuilder details = new StringBuilder();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String key = e.getKey();
            if (!DEFAULTS.containsKey(key)) {
                throw new IllegalArgumentException("Unknown setting: " + key);
            }
            String value = e.getValue() == null ? "" : e.getValue().trim();
            validate(key, value);
            String before = get(key);
            if (before.equals(value)) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO company_setting (key, value) VALUES (?, ?)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                    """).params(key, value).update();
            cache.remove(key);
            details.append(key).append(": ").append(before).append(" -> ").append(value).append("; ");
        }
        if (!details.isEmpty()) {
            audit.record("SETTINGS_UPDATED", "settings", null, details.toString().replaceAll("; $", ""));
        }
        return all();
    }

    private static void validate(String key, String value) {
        switch (key) {
            case "company_name" -> {
                if (value.isBlank() || value.length() > 120) throw new IllegalArgumentException("Company name is required (max 120 characters).");
            }
            case "timezone" -> {
                try {
                    ZoneId.of(value);
                } catch (DateTimeException e) {
                    throw new IllegalArgumentException("Unknown time zone: " + value + " (use a name like Asia/Kolkata).");
                }
            }
            case "overtime_rate_multiplier" -> {
                try {
                    BigDecimal m = new BigDecimal(value);
                    if (m.signum() < 0 || m.compareTo(BigDecimal.TEN) > 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Overtime multiplier must be a number between 0 and 10.");
                }
            }
            case "notify_absence_time" -> {
                try {
                    java.time.LocalTime.parse(value);
                } catch (DateTimeException e) {
                    throw new IllegalArgumentException("Absence alert time must look like 11:00.");
                }
            }
            case "notify_employees_late" -> {
                if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Must be true or false.");
            }
            case "hr_notification_emails" -> {
                for (String email : value.split(",")) {
                    String t = email.trim();
                    if (!t.isEmpty() && !t.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                        throw new IllegalArgumentException("Not an email address: " + t);
                    }
                }
            }
            default -> { }
        }
    }

    public ZoneId zone() {
        return ZoneId.of(get("timezone"));
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zone());
    }

    public BigDecimal overtimeMultiplier() {
        return new BigDecimal(get("overtime_rate_multiplier"));
    }
}
