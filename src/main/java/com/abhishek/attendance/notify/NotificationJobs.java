package com.abhishek.attendance.notify;

import com.abhishek.attendance.engine.AttendanceCalculator;
import com.abhishek.attendance.engine.DayRecord;
import com.abhishek.attendance.engine.DayStatus;
import com.abhishek.attendance.engine.PeriodSummary;
import com.abhishek.attendance.org.Employee;
import com.abhishek.attendance.org.EmployeeRepository;
import com.abhishek.attendance.org.EmployeeService;
import com.abhishek.attendance.payroll.PayrollService;
import com.abhishek.attendance.settings.SettingsService;
import com.abhishek.attendance.web.Csv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Scheduled notifications. Every 5 minutes, once the configured alert time has passed (company time zone):
 * <ul>
 *   <li>daily: who is absent or late today (HR, managers for their team, late employees themselves)</li>
 *   <li>daily: yesterday's irregularities (missing punch, early departure, half day) for managers and HR</li>
 *   <li>Mondays: last week's attendance report by email</li>
 *   <li>1st of the month: last month's attendance and payroll reports by email</li>
 * </ul>
 * Each run claims its period in job_run first, so the cloud API and the office agent never both send it.
 */
@Component
@ConditionalOnProperty(prefix = "attendance.notifications", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationJobs {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy");
    private static final int MAX_NAMES = 40;

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final EmployeeService employees;
    private final AttendanceCalculator calculator;
    private final NotificationService notifications;
    private final MailService mail;
    private final PayrollService payroll;

    public NotificationJobs(JdbcClient jdbc, SettingsService settings, EmployeeService employees,
                            AttendanceCalculator calculator, NotificationService notifications, MailService mail,
                            PayrollService payroll) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.employees = employees;
        this.calculator = calculator;
        this.notifications = notifications;
        this.mail = mail;
        this.payroll = payroll;
    }

    @Scheduled(cron = "${attendance.notifications.cron:0 */5 * * * *}")
    public void tick() {
        LocalDateTime now = settings.now();
        if (now.toLocalTime().isBefore(LocalTime.parse(settings.get("notify_absence_time")))) {
            return;
        }
        LocalDate today = now.toLocalDate();
        run("daily-alerts", today.toString(), () -> dailyAlerts(today));
        run("irregularities", today.minusDays(1).toString(), () -> irregularities(today.minusDays(1)));
        if (today.getDayOfWeek() == DayOfWeek.MONDAY) {
            LocalDate start = today.minusWeeks(1);
            run("weekly-report", start.get(IsoFields.WEEK_BASED_YEAR) + "-W" + start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                    () -> weeklyReport(start, today.minusDays(1)));
        }
        if (today.getDayOfMonth() == 1) {
            YearMonth last = YearMonth.from(today).minusMonths(1);
            run("monthly-report", last.toString(), () -> monthlyReport(last));
        }
    }

    /** Runs a job once per period; a failed run releases its claim so it is retried on the next tick. */
    void run(String job, String period, Runnable work) {
        int claimed = jdbc.sql("INSERT INTO job_run (job, period) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(job, period).update();
        if (claimed == 0) return;
        try {
            work.run();
            log.info("Notification job {} for {} done", job, period);
        } catch (RuntimeException e) {
            jdbc.sql("DELETE FROM job_run WHERE job = ? AND period = ?").params(job, period).update();
            log.error("Notification job {} for {} failed; will retry", job, period, e);
        }
    }

    // ---------------------------------------------------------------- daily

    void dailyAlerts(LocalDate today) {
        List<AttendanceCalculator.EmployeeAttendance> day = compute(today, today);
        List<AttendanceCalculator.EmployeeAttendance> absent = filter(day, d -> d.status() == DayStatus.ABSENT);
        List<AttendanceCalculator.EmployeeAttendance> late = filter(day, DayRecord::late);
        if (!absent.isEmpty() || !late.isEmpty()) {
            String body = section("Absent so far (" + absent.size() + ")", absent, a -> "")
                    + section("Late (" + late.size() + ")", late, a -> " (" + a.days().getFirst().lateMinutes() + " min)");
            String title = "Attendance %s: %d absent, %d late".formatted(DAY.format(today), absent.size(), late.size());
            notifications.notify(notifications.hrUsers(), "DAILY_ABSENCE", title, body.strip(), "/register?date=" + today);
            mailHrList(title, body.strip());
            forEachManager(day, (manager, team) -> {
                var teamAbsent = filter(team, d -> d.status() == DayStatus.ABSENT);
                var teamLate = filter(team, DayRecord::late);
                if (teamAbsent.isEmpty() && teamLate.isEmpty()) return;
                notifications.notify(List.of(manager), "TEAM_ABSENCE",
                        "Your team %s: %d absent, %d late".formatted(DAY.format(today), teamAbsent.size(), teamLate.size()),
                        (section("Absent so far", teamAbsent, a -> "")
                                + section("Late", teamLate, a -> " (" + a.days().getFirst().lateMinutes() + " min)")).strip(),
                        "/register?date=" + today);
            });
        }
        if (Boolean.parseBoolean(settings.get("notify_employees_late"))) {
            for (var a : late) {
                DayRecord d = a.days().getFirst();
                notifications.userOfEmployee(a.employee().id()).ifPresent(u -> notifications.notify(List.of(u), "LATE_ARRIVAL",
                        "You arrived %d minutes late today".formatted(d.lateMinutes()),
                        "Your first punch today was at %s; your shift starts at %s. If this is wrong, request a punch correction."
                                .formatted(d.firstIn().toLocalTime(), d.scheduledStart().toLocalTime()),
                        "/my-attendance"));
            }
        }
    }

    void irregularities(LocalDate day) {
        List<AttendanceCalculator.EmployeeAttendance> all = compute(day, day);
        List<AttendanceCalculator.EmployeeAttendance> odd = filter(all,
                d -> d.missingOut() || d.leftEarly() || d.status() == DayStatus.HALF_DAY);
        if (odd.isEmpty()) return;
        java.util.function.Function<AttendanceCalculator.EmployeeAttendance, String> why = a -> {
            DayRecord d = a.days().getFirst();
            List<String> issues = new ArrayList<>();
            if (d.missingOut()) issues.add("missing punch");
            if (d.status() == DayStatus.HALF_DAY) issues.add("half day");
            if (d.leftEarly()) issues.add("left " + d.earlyMinutes() + " min early");
            return " (" + String.join(", ", issues) + ")";
        };
        String title = "Attendance irregularities on " + DAY.format(day) + ": " + odd.size();
        notifications.notify(notifications.hrUsers(), "IRREGULARITIES", title, section("", odd, why).strip(),
                "/register?date=" + day);
        forEachManager(odd, (manager, team) -> notifications.notify(List.of(manager), "IRREGULARITIES",
                "Your team's irregularities on " + DAY.format(day) + ": " + team.size(), section("", team, why).strip(),
                "/register?date=" + day));
    }

    // ---------------------------------------------------------------- reports

    void weeklyReport(LocalDate from, LocalDate to) {
        List<AttendanceCalculator.EmployeeAttendance> week = compute(from, to);
        String csv = summaryCsv(week);
        String title = "Weekly attendance report: %s to %s".formatted(DAY.format(from), DAY.format(to));
        String body = totals(week) + "\n\nThe full report is attached as a CSV file.";
        sendReport(title, body, List.of(MailService.Attachment.csv("attendance-" + from + "-to-" + to + ".csv", csv)));
    }

    void monthlyReport(YearMonth month) {
        List<AttendanceCalculator.EmployeeAttendance> all = compute(month.atDay(1), month.atEndOfMonth());
        PayrollService.Payroll p = payroll.month(null, month, null);
        String payrollCsv = Csv.of(List.of("Employee ID", "Name", "Department", "Payable days", "Unpaid leave",
                        "Absent days", "Overtime hours", "Base pay", "Overtime pay", "Gross pay"),
                p.rows().stream().map(r -> List.of(r.employeeCode(), r.fullName(), nz(r.departmentName()),
                        "" + r.payableDays(), "" + r.unpaidLeaveDays(), "" + r.absentDays(),
                        String.format("%.2f", r.overtimeMinutes() / 60.0), num(r.basePay()), num(r.overtimePay()),
                        num(r.grossPay()))).toList());
        String title = "Monthly attendance report: " + month.format(DateTimeFormatter.ofPattern("MMMM yyyy"));
        String body = totals(all) + "\nTotal gross pay: " + p.totalGross().toPlainString()
                + "\n\nAttendance and payroll reports are attached as CSV files.";
        sendReport(title, body, List.of(
                MailService.Attachment.csv("attendance-" + month + ".csv", summaryCsv(all)),
                MailService.Attachment.csv("payroll-" + month + ".csv", payrollCsv)));
    }

    private void sendReport(String title, String body, List<MailService.Attachment> attachments) {
        notifications.notify(notifications.hrUsers(), "REPORT", title, body.replace("attached as", "available on the Reports page and emailed as"), "/analytics");
        List<String> to = new ArrayList<>(hrList());
        notifications.hrUsers().forEach(u -> to.add(u.email()));
        mail.send(to, title, body, attachments);
    }

    // ---------------------------------------------------------------- helpers

    private List<AttendanceCalculator.EmployeeAttendance> compute(LocalDate from, LocalDate to) {
        List<Employee> active = employees.search(new EmployeeRepository.Filter(null, null, true, null, null));
        return calculator.compute(active, from, to);
    }

    private static List<AttendanceCalculator.EmployeeAttendance> filter(List<AttendanceCalculator.EmployeeAttendance> list,
                                                                         Predicate<DayRecord> test) {
        return list.stream().filter(a -> !a.days().isEmpty() && test.test(a.days().getFirst())).toList();
    }

    /** Calls back once per manager account with the members of their team found in {@code list}. */
    private void forEachManager(List<AttendanceCalculator.EmployeeAttendance> list,
                                java.util.function.BiConsumer<NotificationService.Recipient, List<AttendanceCalculator.EmployeeAttendance>> action) {
        Map<Long, NotificationService.Recipient> managers = new LinkedHashMap<>();
        Map<Long, List<AttendanceCalculator.EmployeeAttendance>> teams = new LinkedHashMap<>();
        for (var a : list) {
            for (var m : notifications.approversOf(a.employee().id())) {
                if (isHr(m)) continue; // HR already receives the company-wide message
                managers.putIfAbsent(m.userId(), m);
                teams.computeIfAbsent(m.userId(), k -> new ArrayList<>()).add(a);
            }
        }
        teams.forEach((id, team) -> action.accept(managers.get(id), team));
    }

    private boolean isHr(NotificationService.Recipient r) {
        return notifications.hrUsers().stream().anyMatch(h -> h.userId() == r.userId());
    }

    private static String section(String heading, List<AttendanceCalculator.EmployeeAttendance> list,
                                  java.util.function.Function<AttendanceCalculator.EmployeeAttendance, String> suffix) {
        if (list.isEmpty()) return "";
        String names = list.stream().limit(MAX_NAMES)
                .map(a -> "- " + a.employee().fullName() + (a.employee().departmentName() == null ? "" : ", " + a.employee().departmentName()) + suffix.apply(a))
                .collect(Collectors.joining("\n"));
        if (list.size() > MAX_NAMES) names += "\n- and " + (list.size() - MAX_NAMES) + " more";
        return (heading.isEmpty() ? "" : heading + ":\n") + names + "\n\n";
    }

    private static String totals(List<AttendanceCalculator.EmployeeAttendance> list) {
        double present = 0, absent = 0;
        int late = 0, leave = 0;
        long ot = 0;
        for (var a : list) {
            PeriodSummary s = a.summary();
            present += s.presentDays();
            absent += s.absentDays();
            late += s.lateDays();
            leave += s.leaveDays();
            ot += s.overtimeMinutes();
        }
        return "Employees: %d\nPresent days: %s\nAbsent days: %s\nLeave days: %d\nLate arrivals: %d\nOvertime hours: %.1f"
                .formatted(list.size(), fmt(present), fmt(absent), leave, late, ot / 60.0);
    }

    private static String summaryCsv(List<AttendanceCalculator.EmployeeAttendance> list) {
        return Csv.of(List.of("Employee ID", "Name", "Department", "Working days", "Present days", "Absent days",
                        "Leave days", "Late days", "Late minutes", "Early departures", "Overtime hours", "Attendance %"),
                list.stream().map(a -> {
                    PeriodSummary s = a.summary();
                    return List.of(a.employee().employeeCode(), a.employee().fullName(), nz(a.employee().departmentName()),
                            "" + s.workingDays(), fmt(s.presentDays()), fmt(s.absentDays()), "" + s.leaveDays(),
                            "" + s.lateDays(), "" + s.lateMinutes(), "" + s.earlyDays(),
                            String.format("%.1f", s.overtimeMinutes() / 60.0),
                            s.attendancePercent() == null ? "" : s.attendancePercent().toString());
                }).toList());
    }

    private void mailHrList(String subject, String body) {
        mail.send(hrList(), subject, body + "\n\n-- Attendance system");
    }

    private List<String> hrList() {
        return Arrays.stream(settings.get("hr_notification_emails").split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String num(java.math.BigDecimal n) {
        return n == null ? "" : n.toPlainString();
    }
}
