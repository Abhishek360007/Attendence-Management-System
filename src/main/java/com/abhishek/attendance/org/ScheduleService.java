package com.abhishek.attendance.org;

import com.abhishek.attendance.audit.AuditService;
import com.abhishek.attendance.web.ConflictException;
import com.abhishek.attendance.web.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service
public class ScheduleService {

    public record ScheduleInput(String name, java.time.LocalTime startTime, java.time.LocalTime endTime,
                                int lateGraceMinutes, int earlyGraceMinutes, int overtimeMinMinutes,
                                int halfDayMinutes, Set<DayOfWeek> weeklyOffs, boolean isDefault) {
    }

    public record Holiday(Long id, LocalDate holidayDate, String name) {
    }

    private static final String COLUMNS = """
            id, name, start_time, end_time, late_grace_minutes, early_grace_minutes, overtime_min_minutes,
            half_day_minutes, weekly_offs, is_default""";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ScheduleService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- schedules

    public List<WorkSchedule> list() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM work_schedule ORDER BY is_default DESC, LOWER(name)")
                .query(WorkSchedule.class).list();
    }

    public WorkSchedule get(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM work_schedule WHERE id = ?").param(id)
                .query(WorkSchedule.class).optional().orElseThrow(() -> new NotFoundException("Schedule", id));
    }

    public WorkSchedule defaultSchedule() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM work_schedule ORDER BY is_default DESC, id LIMIT 1")
                .query(WorkSchedule.class).single();
    }

    @Transactional
    public WorkSchedule create(ScheduleInput in) {
        validate(in);
        if (in.isDefault()) {
            jdbc.sql("UPDATE work_schedule SET is_default = FALSE WHERE is_default").update();
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO work_schedule (name, start_time, end_time, late_grace_minutes, early_grace_minutes,
                                                   overtime_min_minutes, half_day_minutes, weekly_offs, is_default)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(in.name().trim(), in.startTime(), in.endTime(), in.lateGraceMinutes(), in.earlyGraceMinutes(),
                        in.overtimeMinMinutes(), in.halfDayMinutes(), WorkSchedule.formatOffs(in.weeklyOffs()), in.isDefault())
                .update(keys, "id");
        long id = keys.getKey().longValue();
        audit.record("SCHEDULE_CREATED", "schedule", id, describe(in));
        return get(id);
    }

    @Transactional
    public WorkSchedule update(long id, ScheduleInput in) {
        WorkSchedule before = get(id);
        validate(in);
        if (before.isDefault() && !in.isDefault()) {
            throw new ConflictException("Make another schedule the default first.");
        }
        if (in.isDefault()) {
            jdbc.sql("UPDATE work_schedule SET is_default = FALSE WHERE is_default AND id <> ?").param(id).update();
        }
        jdbc.sql("""
                        UPDATE work_schedule SET name = ?, start_time = ?, end_time = ?, late_grace_minutes = ?,
                               early_grace_minutes = ?, overtime_min_minutes = ?, half_day_minutes = ?, weekly_offs = ?,
                               is_default = ?
                        WHERE id = ?""")
                .params(in.name().trim(), in.startTime(), in.endTime(), in.lateGraceMinutes(), in.earlyGraceMinutes(),
                        in.overtimeMinMinutes(), in.halfDayMinutes(), WorkSchedule.formatOffs(in.weeklyOffs()),
                        in.isDefault(), id)
                .update();
        audit.record("SCHEDULE_UPDATED", "schedule", id, "was: " + describe(before) + " | now: " + describe(in));
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        WorkSchedule s = get(id);
        if (s.isDefault()) {
            throw new ConflictException("The default schedule cannot be deleted.");
        }
        int used = jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM department WHERE work_schedule_id = ?)
                     + (SELECT COUNT(*) FROM employee WHERE work_schedule_id = ?)""")
                .params(id, id).query(Integer.class).single();
        if (used > 0) {
            throw new ConflictException("This schedule is used by " + used + " department(s) or employee(s).");
        }
        jdbc.sql("DELETE FROM work_schedule WHERE id = ?").param(id).update();
        audit.record("SCHEDULE_DELETED", "schedule", id, s.name());
    }

    private static void validate(ScheduleInput in) {
        if (in.name() == null || in.name().isBlank()) throw new IllegalArgumentException("Name is required.");
        if (in.startTime() == null || in.endTime() == null) throw new IllegalArgumentException("Start and end time are required.");
        if (in.startTime().equals(in.endTime())) throw new IllegalArgumentException("Start and end time must differ.");
        if (in.weeklyOffs() != null && in.weeklyOffs().size() == 7) throw new IllegalArgumentException("At least one working day is needed.");
    }

    private static String describe(ScheduleInput s) {
        return "%s %s-%s, grace %d/%d min, offs %s".formatted(s.name(), s.startTime(), s.endTime(),
                s.lateGraceMinutes(), s.earlyGraceMinutes(), WorkSchedule.formatOffs(s.weeklyOffs()));
    }

    private static String describe(WorkSchedule s) {
        return "%s %s-%s, grace %d/%d min, offs %s".formatted(s.name(), s.startTime(), s.endTime(),
                s.lateGraceMinutes(), s.earlyGraceMinutes(), s.weeklyOffs());
    }

    // ---------------------------------------------------------------- holidays

    public List<Holiday> holidays(LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT id, holiday_date, name FROM holiday WHERE holiday_date BETWEEN ? AND ? ORDER BY holiday_date")
                .params(from, to).query(Holiday.class).list();
    }

    @Transactional
    public Holiday addHoliday(LocalDate date, String name) {
        if (date == null || name == null || name.isBlank()) throw new IllegalArgumentException("Date and name are required.");
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO holiday (holiday_date, name) VALUES (?, ?)").params(date, name.trim()).update(keys, "id");
        long id = keys.getKey().longValue();
        audit.record("HOLIDAY_ADDED", "holiday", id, date + " " + name.trim());
        return new Holiday(id, date, name.trim());
    }

    @Transactional
    public void deleteHoliday(long id) {
        Holiday h = jdbc.sql("SELECT id, holiday_date, name FROM holiday WHERE id = ?").param(id)
                .query(Holiday.class).optional().orElseThrow(() -> new NotFoundException("Holiday", id));
        jdbc.sql("DELETE FROM holiday WHERE id = ?").param(id).update();
        audit.record("HOLIDAY_DELETED", "holiday", id, h.holidayDate() + " " + h.name());
    }
}
