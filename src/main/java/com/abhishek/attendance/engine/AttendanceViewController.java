package com.abhishek.attendance.engine;

import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.org.AccessPolicy;
import com.abhishek.attendance.org.Employee;
import com.abhishek.attendance.org.EmployeeRepository;
import com.abhishek.attendance.org.EmployeeService;
import com.abhishek.attendance.org.WorkSchedule;
import com.abhishek.attendance.settings.SettingsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/**
 * Calculated attendance: daily register, an employee's days, punctuality and today's overview.
 * Results are limited to the employees the caller may see.
 */
@RestController
@RequestMapping("/api")
public class AttendanceViewController {

    public record EmployeeRef(long id, String employeeCode, String fullName, String departmentName, String designation) {
        static EmployeeRef of(Employee e) {
            return new EmployeeRef(e.id(), e.employeeCode(), e.fullName(), e.departmentName(), e.designation());
        }
    }

    public record RegisterRow(EmployeeRef employee, DayRecord day) {
    }

    public record EmployeeMonth(EmployeeRef employee, WorkSchedule schedule, LocalDate from, LocalDate to,
                                List<DayRecord> days, PeriodSummary summary) {
    }

    public record SummaryRow(EmployeeRef employee, PeriodSummary summary) {
    }

    public record Today(LocalDate date, int employees, int present, int late, int absent, int onLeave,
                        int notYetIn, int off, List<RegisterRow> rows) {
    }

    private final AttendanceCalculator calculator;
    private final EmployeeService employees;
    private final AccessPolicy access;
    private final SettingsService settings;

    public AttendanceViewController(AttendanceCalculator calculator, EmployeeService employees, AccessPolicy access,
                                    SettingsService settings) {
        this.calculator = calculator;
        this.employees = employees;
        this.access = access;
        this.settings = settings;
    }

    /** Every visible active employee's status for one date. */
    @GetMapping("/attendance/register")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'MANAGER', 'API')")
    public List<RegisterRow> register(@AuthenticationPrincipal AppUser user,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam(required = false) Long departmentId) {
        LocalDate d = date != null ? date : settings.today();
        return calculator.compute(visible(user, departmentId), d, d).stream()
                .filter(a -> !a.days().isEmpty())
                .map(a -> new RegisterRow(EmployeeRef.of(a.employee()), a.days().getFirst()))
                .toList();
    }

    /** One employee's days and totals for a month (default: this month) or a date range. */
    @GetMapping("/employees/{id}/attendance")
    public EmployeeMonth employeeAttendance(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                            @RequestParam(required = false) YearMonth month,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        access.checkCanView(user, id);
        if (from == null || to == null) {
            YearMonth m = month != null ? month : YearMonth.from(settings.today());
            from = m.atDay(1);
            to = m.atEndOfMonth();
        }
        var a = calculator.forEmployee(employees.get(id), from, to);
        return new EmployeeMonth(EmployeeRef.of(a.employee()), a.schedule(), from, to, a.days(), a.summary());
    }

    /** Totals per employee: punctuality, absences, overtime. */
    @GetMapping("/reports/attendance-summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'MANAGER', 'API')")
    public List<SummaryRow> summary(@AuthenticationPrincipal AppUser user,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) Long departmentId) {
        return calculator.compute(visible(user, departmentId), from, to).stream()
                .map(a -> new SummaryRow(EmployeeRef.of(a.employee()), a.summary()))
                .sorted(Comparator.comparing((SummaryRow r) -> r.employee().fullName().toLowerCase()))
                .toList();
    }

    /** Today's counts for the caller's scope (everyone for HR, the team for managers, themselves for employees). */
    @GetMapping("/attendance/today")
    public Today today(@AuthenticationPrincipal AppUser user) {
        LocalDate d = settings.today();
        List<RegisterRow> rows = calculator.compute(visible(user, null), d, d).stream()
                .filter(a -> !a.days().isEmpty())
                .map(a -> new RegisterRow(EmployeeRef.of(a.employee()), a.days().getFirst()))
                .toList();
        int present = 0, late = 0, absent = 0, leave = 0, notYet = 0, off = 0;
        for (RegisterRow r : rows) {
            switch (r.day().status()) {
                case PRESENT, HALF_DAY -> { present++; if (r.day().late()) late++; }
                case ABSENT -> absent++;
                case LEAVE -> leave++;
                case UPCOMING -> notYet++;
                case HOLIDAY, WEEKLY_OFF -> off++;
            }
        }
        return new Today(d, rows.size(), present, late, absent, leave, notYet, off, rows);
    }

    private List<Employee> visible(AppUser user, Long departmentId) {
        return employees.search(access.scope(user, new EmployeeRepository.Filter(null, departmentId, true, null, null)));
    }
}
