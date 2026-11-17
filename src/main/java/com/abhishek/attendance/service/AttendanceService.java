package com.abhishek.attendance.service;

import com.abhishek.attendance.model.AttendanceRecord;
import com.abhishek.attendance.model.DailySummary;
import com.abhishek.attendance.repository.AttendanceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AttendanceService {

    /** Upper bound on rows pulled for a daily report. */
    private static final int REPORT_LIMIT = 100_000;

    private final AttendanceRepository repository;

    public AttendanceService(AttendanceRepository repository) {
        this.repository = repository;
    }

    public List<AttendanceRecord> findAfter(long id, int limit) {
        return repository.findAfterId(id, limit);
    }

    public List<AttendanceRecord> search(String employeeId, LocalDate from, LocalDate to, int page, int size) {
        return repository.search(employeeId, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), size, page * size);
    }

    /**
     * Builds one first-in / last-out row per employee per day.
     */
    public List<DailySummary> dailySummary(String employeeId, LocalDate from, LocalDate to) {
        List<AttendanceRecord> records = repository.search(
                employeeId, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), REPORT_LIMIT, 0);

        Map<String, List<AttendanceRecord>> grouped = new LinkedHashMap<>();
        for (AttendanceRecord r : records) {
            String key = r.employeeId() + "|" + r.checkTime().toLocalDate();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<DailySummary> result = new ArrayList<>();
        for (List<AttendanceRecord> day : grouped.values()) {
            AttendanceRecord first = day.get(0);
            LocalDateTime firstIn = day.get(0).checkTime();
            LocalDateTime lastOut = day.get(day.size() - 1).checkTime();
            result.add(DailySummary.of(first.employeeId(), first.employeeName(),
                    firstIn.toLocalDate(), firstIn, lastOut, day.size()));
        }
        result.sort(Comparator.comparing(DailySummary::date).thenComparing(DailySummary::employeeId));
        return result;
    }

    public long totalRecords() {
        return repository.count();
    }

    public long lastSourceId() {
        return repository.maxSourceId();
    }

    public LocalDateTime latestPunchAt() {
        return repository.maxCheckTime();
    }
}
