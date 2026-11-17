package com.abhishek.attendance.engine;

import com.abhishek.attendance.org.Department;
import com.abhishek.attendance.org.Employee;
import com.abhishek.attendance.org.EmployeeService;
import com.abhishek.attendance.org.ScheduleService;
import com.abhishek.attendance.org.WorkSchedule;
import com.abhishek.attendance.settings.SettingsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads schedules, holidays, approved leave and punches, and runs {@link AttendanceEngine} for many employees.
 */
@Service
public class AttendanceCalculator {

    public record EmployeeAttendance(Employee employee, WorkSchedule schedule, List<DayRecord> days,
                                     PeriodSummary summary) {
    }

    /** Ranges longer than this are refused, to keep requests fast on a small server. */
    public static final int MAX_DAYS = 400;

    private final JdbcClient jdbc;
    private final ScheduleService schedules;
    private final EmployeeService employees;
    private final SettingsService settings;
    private final ObjectProvider<ApprovedLeaveSource> leaveSource;

    public AttendanceCalculator(JdbcClient jdbc, ScheduleService schedules, EmployeeService employees,
                                SettingsService settings, ObjectProvider<ApprovedLeaveSource> leaveSource) {
        this.jdbc = jdbc;
        this.schedules = schedules;
        this.employees = employees;
        this.settings = settings;
        this.leaveSource = leaveSource;
    }

    public EmployeeAttendance forEmployee(Employee e, LocalDate from, LocalDate to) {
        return compute(List.of(e), from, to).getFirst();
    }

    public List<EmployeeAttendance> compute(List<Employee> list, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("'to' must not be before 'from'");
        if (from.plusDays(MAX_DAYS).isBefore(to)) throw new IllegalArgumentException("Range is limited to " + MAX_DAYS + " days");
        if (list.isEmpty()) return List.of();

        Map<Long, WorkSchedule> scheduleById = schedules.list().stream()
                .collect(Collectors.toMap(WorkSchedule::id, Function.identity()));
        WorkSchedule fallback = schedules.defaultSchedule();
        Map<Long, Long> deptSchedule = new HashMap<>();
        for (Department d : employees.departments()) {
            if (d.workScheduleId() != null) deptSchedule.put(d.id(), d.workScheduleId());
        }
        Map<LocalDate, String> holidays = schedules.holidays(from, to).stream()
                .collect(Collectors.toMap(ScheduleService.Holiday::holidayDate, ScheduleService.Holiday::name));
        List<Long> ids = list.stream().map(Employee::id).toList();
        ApprovedLeaveSource source = leaveSource.getIfAvailable();
        Map<Long, Map<LocalDate, AttendanceEngine.LeaveDay>> leaves = source == null ? Map.of()
                : source.approvedLeaves(ids, from, to);
        Map<String, List<LocalDateTime>> punches = punches(
                list.stream().map(Employee::deviceUserId).filter(Objects::nonNull).toList(), from, to);
        LocalDateTime now = settings.now();

        List<EmployeeAttendance> out = new ArrayList<>(list.size());
        for (Employee e : list) {
            WorkSchedule s = resolve(e, scheduleById, deptSchedule, fallback);
            List<DayRecord> days = AttendanceEngine.days(s, e.joinedOn(), e.leftOn(), from, to,
                    e.deviceUserId() == null ? List.of() : punches.getOrDefault(e.deviceUserId(), List.of()),
                    holidays, leaves.getOrDefault(e.id(), Map.of()), now);
            out.add(new EmployeeAttendance(e, s, days, PeriodSummary.of(days)));
        }
        return out;
    }

    public WorkSchedule scheduleOf(Employee e) {
        Map<Long, WorkSchedule> byId = schedules.list().stream().collect(Collectors.toMap(WorkSchedule::id, Function.identity()));
        Map<Long, Long> dept = new HashMap<>();
        employees.departments().forEach(d -> { if (d.workScheduleId() != null) dept.put(d.id(), d.workScheduleId()); });
        return resolve(e, byId, dept, schedules.defaultSchedule());
    }

    private static WorkSchedule resolve(Employee e, Map<Long, WorkSchedule> byId, Map<Long, Long> deptSchedule,
                                        WorkSchedule fallback) {
        Long id = e.workScheduleId();
        if (id == null && e.departmentId() != null) id = deptSchedule.get(e.departmentId());
        WorkSchedule s = id == null ? null : byId.get(id);
        return s == null ? fallback : s;
    }

    /** Effective (not voided) punch times per device user id, covering the day windows of the range. */
    private Map<String, List<LocalDateTime>> punches(Collection<String> deviceIds, LocalDate from, LocalDate to) {
        if (deviceIds.isEmpty()) return Map.of();
        Map<String, List<LocalDateTime>> out = new HashMap<>();
        jdbc.sql("""
                        SELECT employee_id, check_time FROM attendance_record
                        WHERE employee_id IN (:ids) AND NOT voided AND check_time >= :from AND check_time < :to
                        ORDER BY employee_id, check_time""")
                .param("ids", deviceIds)
                .param("from", Timestamp.valueOf(from.minusDays(1).atStartOfDay()))
                .param("to", Timestamp.valueOf(to.plusDays(2).atStartOfDay()))
                .query(rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getTimestamp(2).toLocalDateTime());
                });
        return out;
    }
}
