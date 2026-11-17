package com.abhishek.attendance.payroll;

import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.engine.AttendanceCalculator;
import com.abhishek.attendance.engine.DayRecord;
import com.abhishek.attendance.engine.PeriodSummary;
import com.abhishek.attendance.org.AccessPolicy;
import com.abhishek.attendance.org.Employee;
import com.abhishek.attendance.org.EmployeeRepository;
import com.abhishek.attendance.org.EmployeeService;
import com.abhishek.attendance.org.ScheduleService;
import com.abhishek.attendance.org.WorkSchedule;
import com.abhishek.attendance.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Attendance-based pay for a month.
 * <ul>
 *   <li>Payable days = days employed in the month − absent days − unpaid leave days. Weekly offs, holidays and paid
 *       leave are paid; a half day counts as half absent.</li>
 *   <li>Base pay = monthly salary ÷ calendar days in the month × payable days.</li>
 *   <li>Overtime pay = hourly rate × overtime hours × the overtime multiplier (company setting), where the hourly
 *       rate is the monthly salary ÷ the schedule's working hours in the month.</li>
 * </ul>
 */
@Service
public class PayrollService {

    public record Row(long employeeId, String employeeCode, String fullName, String departmentName,
                      String designation, BigDecimal monthlySalary, int daysEmployed, int workingDays,
                      double presentDays, int halfDays, int paidLeaveDays, int unpaidLeaveDays, double absentDays,
                      int holidays, int weeklyOffs, int lateDays, long lateMinutes, int earlyDays,
                      long overtimeMinutes, double payableDays, BigDecimal dailyRate, BigDecimal hourlyRate,
                      BigDecimal basePay, BigDecimal overtimePay, BigDecimal grossPay) {
    }

    public record Payroll(YearMonth month, boolean complete, BigDecimal overtimeMultiplier, List<Row> rows,
                          BigDecimal totalGross) {
    }

    private final AttendanceCalculator calculator;
    private final EmployeeService employees;
    private final AccessPolicy access;
    private final ScheduleService schedules;
    private final SettingsService settings;

    public PayrollService(AttendanceCalculator calculator, EmployeeService employees, AccessPolicy access,
                          ScheduleService schedules, SettingsService settings) {
        this.calculator = calculator;
        this.employees = employees;
        this.access = access;
        this.schedules = schedules;
        this.settings = settings;
    }

    public Payroll month(AppUser user, YearMonth month, Long departmentId) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        // Everyone employed for at least one day of the month, including people who have since left.
        List<Employee> list = employees.search(access.scope(user,
                        new EmployeeRepository.Filter(null, departmentId, null, null, null))).stream()
                .filter(e -> e.joinedOn() == null || !e.joinedOn().isAfter(to))
                .filter(e -> e.leftOn() == null || !e.leftOn().isBefore(from))
                .filter(e -> e.active() || e.leftOn() != null)
                .toList();
        BigDecimal multiplier = settings.overtimeMultiplier();
        Set<LocalDate> holidays = schedules.holidays(from, to).stream().map(ScheduleService.Holiday::holidayDate)
                .collect(Collectors.toSet());
        List<Row> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (AttendanceCalculator.EmployeeAttendance a : calculator.compute(list, from, to)) {
            if (a.days().isEmpty()) continue; // not employed, or not yet tracked, in this month
            Row r = row(a, month, multiplier, holidays);
            rows.add(r);
            if (r.grossPay() != null) total = total.add(r.grossPay());
        }
        boolean complete = settings.today().isAfter(to);
        return new Payroll(month, complete, multiplier, rows, total);
    }

    static Row row(AttendanceCalculator.EmployeeAttendance a, YearMonth month, BigDecimal multiplier,
                   Set<LocalDate> holidays) {
        Employee e = a.employee();
        PeriodSummary s = a.summary();
        List<DayRecord> days = a.days();
        int employed = days.size();
        double payable = Math.max(0, employed - s.absentDays() - s.unpaidLeaveDays());
        BigDecimal salary = e.monthlySalary();
        BigDecimal daily = null, hourly = null, base = null, ot = null, gross = null;
        if (salary != null) {
            daily = salary.divide(BigDecimal.valueOf(month.lengthOfMonth()), 4, RoundingMode.HALF_UP);
            base = daily.multiply(BigDecimal.valueOf(payable)).setScale(2, RoundingMode.HALF_UP);
            long scheduledMinutes = scheduledMinutesInMonth(a.schedule(), month, holidays);
            if (scheduledMinutes > 0) {
                hourly = salary.multiply(BigDecimal.valueOf(60))
                        .divide(BigDecimal.valueOf(scheduledMinutes), 4, RoundingMode.HALF_UP);
                ot = hourly.multiply(BigDecimal.valueOf(s.overtimeMinutes()))
                        .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP)
                        .multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            } else {
                ot = BigDecimal.ZERO.setScale(2);
            }
            gross = base.add(ot);
            daily = daily.setScale(2, RoundingMode.HALF_UP);
            hourly = hourly == null ? null : hourly.setScale(2, RoundingMode.HALF_UP);
        }
        return new Row(e.id(), e.employeeCode(), e.fullName(), e.departmentName(), e.designation(), salary, employed,
                s.workingDays(), s.presentDays(), s.halfDays(), s.paidLeaveDays(), s.unpaidLeaveDays(), s.absentDays(),
                s.holidays(), s.weeklyOffs(), s.lateDays(), s.lateMinutes(), s.earlyDays(), s.overtimeMinutes(),
                payable, daily, hourly, base, ot, gross);
    }

    /** The schedule's working minutes in the whole month, ignoring joining dates (the basis of the hourly rate). */
    static long scheduledMinutesInMonth(WorkSchedule s, YearMonth month, Set<LocalDate> holidays) {
        long days = 0;
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plus(1, ChronoUnit.DAYS)) {
            if (!s.weeklyOffDays().contains(d.getDayOfWeek()) && !holidays.contains(d)) days++;
        }
        return days * s.scheduledMinutes();
    }
}
