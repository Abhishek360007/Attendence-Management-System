package com.abhishek.attendance.analytics;

import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.engine.AttendanceCalculator;
import com.abhishek.attendance.engine.DayRecord;
import com.abhishek.attendance.engine.PeriodSummary;
import com.abhishek.attendance.org.AccessPolicy;
import com.abhishek.attendance.org.Employee;
import com.abhishek.attendance.org.EmployeeRepository;
import com.abhishek.attendance.org.EmployeeService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AnalyticsService {

    /** Totals for a group of employees over a period. */
    public record Kpis(int employees, Double attendancePercent, double presentDays, double absentDays, int lateArrivals,
                       long lateMinutes, int earlyDepartures, double overtimeHours, int leaveDays,
                       Double avgWorkedHoursPerDay, double punctualityPercent) {
    }

    public record MonthPoint(YearMonth month, Kpis kpis) {
    }

    public record DepartmentRow(Long departmentId, String department, Kpis kpis) {
    }

    public record EmployeeStat(long employeeId, String employeeCode, String fullName, String departmentName,
                               double presentDays, int lateDays, long lateMinutes, double absentDays,
                               double overtimeHours, double onTimePercent) {
    }

    public record Report(YearMonth month, Kpis current, Kpis previous, List<MonthPoint> trend,
                         List<DepartmentRow> departments, List<EmployeeStat> mostPunctual,
                         List<EmployeeStat> frequentAbsentees, List<EmployeeStat> overtimeLeaders) {
    }

    /** Employees need at least this many present days in the month to rank as most punctual. */
    static final int MIN_DAYS_FOR_PUNCTUALITY = 5;

    private final AttendanceCalculator calculator;
    private final EmployeeService employees;
    private final AccessPolicy access;

    public AnalyticsService(AttendanceCalculator calculator, EmployeeService employees, AccessPolicy access) {
        this.calculator = calculator;
        this.employees = employees;
        this.access = access;
    }

    public Report report(AppUser user, YearMonth month, int months, Long departmentId) {
        int n = Math.max(2, Math.min(months, 12));
        YearMonth first = month.minusMonths(n - 1);
        LocalDate from = first.atDay(1);
        LocalDate to = month.atEndOfMonth();
        List<Employee> list = employees.search(access.scope(user,
                        new EmployeeRepository.Filter(null, departmentId, null, null, null))).stream()
                .filter(e -> e.joinedOn() == null || !e.joinedOn().isAfter(to))
                .filter(e -> e.leftOn() == null || !e.leftOn().isBefore(from))
                .filter(e -> e.active() || e.leftOn() != null)
                .toList();
        List<AttendanceCalculator.EmployeeAttendance> all = calculator.compute(list, from, to);

        // employee -> month -> summary
        Map<Long, Map<YearMonth, PeriodSummary>> byMonth = new LinkedHashMap<>();
        for (var a : all) {
            Map<YearMonth, List<DayRecord>> split = new TreeMap<>();
            for (DayRecord d : a.days()) split.computeIfAbsent(YearMonth.from(d.date()), k -> new ArrayList<>()).add(d);
            Map<YearMonth, PeriodSummary> sums = new TreeMap<>();
            split.forEach((m, days) -> sums.put(m, PeriodSummary.of(days)));
            byMonth.put(a.employee().id(), sums);
        }

        List<MonthPoint> trend = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(month); m = m.plusMonths(1)) {
            YearMonth mm = m;
            trend.add(new MonthPoint(m, kpis(all.stream().map(a -> byMonth.get(a.employee().id()).get(mm)).toList())));
        }

        Map<String, List<PeriodSummary>> perDept = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Long> deptIds = new LinkedHashMap<>();
        List<EmployeeStat> stats = new ArrayList<>();
        for (var a : all) {
            PeriodSummary s = byMonth.get(a.employee().id()).get(month);
            if (s == null) continue;
            String dept = a.employee().departmentName() == null ? "No department" : a.employee().departmentName();
            perDept.computeIfAbsent(dept, k -> new ArrayList<>()).add(s);
            deptIds.putIfAbsent(dept, a.employee().departmentId());
            stats.add(stat(a.employee(), s));
        }
        List<DepartmentRow> departments = perDept.entrySet().stream()
                .map(e -> new DepartmentRow(deptIds.get(e.getKey()), e.getKey(), kpis(e.getValue())))
                .toList();

        List<EmployeeStat> punctual = stats.stream()
                .filter(s -> s.presentDays() >= MIN_DAYS_FOR_PUNCTUALITY)
                .sorted(Comparator.comparingDouble(EmployeeStat::onTimePercent).reversed()
                        .thenComparingLong(EmployeeStat::lateMinutes)
                        .thenComparing(Comparator.comparingDouble(EmployeeStat::presentDays).reversed()))
                .limit(5).toList();
        List<EmployeeStat> absentees = stats.stream().filter(s -> s.absentDays() > 0)
                .sorted(Comparator.comparingDouble(EmployeeStat::absentDays).reversed()).limit(5).toList();
        List<EmployeeStat> overtime = stats.stream().filter(s -> s.overtimeHours() > 0)
                .sorted(Comparator.comparingDouble(EmployeeStat::overtimeHours).reversed()).limit(5).toList();

        Kpis current = trend.getLast().kpis();
        Kpis previous = trend.get(trend.size() - 2).kpis();
        return new Report(month, current, previous, trend, departments, punctual, absentees, overtime);
    }

    static Kpis kpis(List<PeriodSummary> summaries) {
        int people = 0, late = 0, early = 0, leave = 0, workingPresent = 0;
        double present = 0, absent = 0;
        long lateMin = 0, ot = 0, worked = 0;
        int expected = 0;
        for (PeriodSummary s : summaries) {
            if (s == null) continue;
            people++;
            present += s.presentDays();
            absent += s.absentDays();
            late += s.lateDays();
            lateMin += s.lateMinutes();
            early += s.earlyDays();
            ot += s.overtimeMinutes();
            leave += s.leaveDays();
            worked += s.workedMinutes();
            expected += s.workingDays() - s.leaveDays();
            workingPresent += (int) Math.ceil(s.presentDays());
        }
        Double pct = expected > 0 ? round1(present * 100 / expected) : null;
        Double avg = present > 0 ? round1(worked / 60.0 / present) : null;
        double punctual = workingPresent > 0 ? round1((workingPresent - late) * 100.0 / workingPresent) : 0;
        return new Kpis(people, pct, present, absent, late, lateMin, early, round1(ot / 60.0), leave, avg, punctual);
    }

    private static EmployeeStat stat(Employee e, PeriodSummary s) {
        int presentDays = (int) Math.ceil(s.presentDays());
        double onTime = presentDays > 0 ? round1((presentDays - s.lateDays()) * 100.0 / presentDays) : 0;
        return new EmployeeStat(e.id(), e.employeeCode(), e.fullName(), e.departmentName(), s.presentDays(),
                s.lateDays(), s.lateMinutes(), s.absentDays(), round1(s.overtimeMinutes() / 60.0), onTime);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
