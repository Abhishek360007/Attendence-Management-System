package com.abhishek.attendance.payroll;

import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.settings.SettingsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/payroll")
@PreAuthorize("hasAnyRole('ADMIN', 'HR', 'API')")
public class PayrollController {

    private final PayrollService payroll;
    private final SettingsService settings;

    public PayrollController(PayrollService payroll, SettingsService settings) {
        this.payroll = payroll;
        this.settings = settings;
    }

    @GetMapping
    public PayrollService.Payroll month(@AuthenticationPrincipal AppUser user,
                                        @RequestParam(required = false) YearMonth month,
                                        @RequestParam(required = false) Long departmentId) {
        return payroll.month(user, month != null ? month : YearMonth.from(settings.today()).minusMonths(1), departmentId);
    }

    /** The same data as a CSV file, for payroll software. */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AppUser user,
                                         @RequestParam(required = false) YearMonth month,
                                         @RequestParam(required = false) Long departmentId) {
        PayrollService.Payroll p = month(user, month, departmentId);
        List<String> header = List.of("Employee ID", "Name", "Department", "Designation", "Monthly salary",
                "Days employed", "Working days", "Present days", "Half days", "Paid leave", "Unpaid leave",
                "Absent days", "Holidays", "Weekly offs", "Late days", "Late minutes", "Early departures",
                "Overtime hours", "Payable days", "Daily rate", "Hourly rate", "Base pay", "Overtime pay", "Gross pay");
        String body = com.abhishek.attendance.web.Csv.of(header, p.rows().stream().map(r -> List.of(
                        r.employeeCode(), r.fullName(), str(r.departmentName()), str(r.designation()), num(r.monthlySalary()),
                        "" + r.daysEmployed(), "" + r.workingDays(), "" + r.presentDays(), "" + r.halfDays(),
                        "" + r.paidLeaveDays(), "" + r.unpaidLeaveDays(), "" + r.absentDays(), "" + r.holidays(),
                        "" + r.weeklyOffs(), "" + r.lateDays(), "" + r.lateMinutes(), "" + r.earlyDays(),
                        String.format("%.2f", r.overtimeMinutes() / 60.0), "" + r.payableDays(), num(r.dailyRate()),
                        num(r.hourlyRate()), num(r.basePay()), num(r.overtimePay()), num(r.grossPay()))).toList());
        // The byte-order mark makes Excel open the file as UTF-8.
        byte[] bytes = (((char) 0xFEFF) + body).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payroll-" + p.month() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String num(BigDecimal n) {
        return n == null ? "" : n.toPlainString();
    }
}
