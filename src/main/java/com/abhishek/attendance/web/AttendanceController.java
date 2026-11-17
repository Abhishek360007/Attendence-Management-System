package com.abhishek.attendance.web;

import com.abhishek.attendance.model.AttendanceRecord;
import com.abhishek.attendance.model.DailySummary;
import com.abhishek.attendance.service.AttendanceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/attendance")
@PreAuthorize("hasAnyRole('ADMIN', 'HR', 'API')")
public class AttendanceController {

    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    /**
     * Search punches. Dates are inclusive; defaults to today.
     */
    @GetMapping
    public List<AttendanceRecord> search(
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int size) {
        DateRange range = DateRange.of(from, to);
        return service.search(employeeId, range.from(), range.to(), page, size);
    }

    @GetMapping("/employees/{employeeId}")
    public List<AttendanceRecord> byEmployee(
            @PathVariable String employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int size) {
        DateRange range = DateRange.of(from, to);
        return service.search(employeeId, range.from(), range.to(), page, size);
    }

    /**
     * First-in / last-out per employee per day.
     */
    @GetMapping("/summary")
    public List<DailySummary> summary(
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DateRange range = DateRange.of(from, to);
        return service.dailySummary(employeeId, range.from(), range.to());
    }

    /**
     * Incremental feed for downstream systems: records stored after record {@code afterId} (the {@code id} field),
     * in the order they were stored. Pass the last {@code id} you received to get the next page.
     */
    @GetMapping("/feed")
    public List<AttendanceRecord> feed(
            @RequestParam(defaultValue = "0") @Min(0) long afterId,
            @RequestParam(defaultValue = "500") @Min(1) @Max(5000) int limit) {
        return service.findAfter(afterId, limit);
    }
}
