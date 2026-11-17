package com.abhishek.attendance.leave;

import com.abhishek.attendance.audit.AuditService;
import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.auth.Role;
import com.abhishek.attendance.engine.ApprovedLeaveSource;
import com.abhishek.attendance.engine.AttendanceCalculator;
import com.abhishek.attendance.engine.AttendanceEngine;
import com.abhishek.attendance.notify.Events;
import com.abhishek.attendance.org.AccessPolicy;
import com.abhishek.attendance.org.Employee;
import com.abhishek.attendance.org.EmployeeRepository;
import com.abhishek.attendance.org.EmployeeService;
import com.abhishek.attendance.org.ScheduleService;
import com.abhishek.attendance.org.WorkSchedule;
import com.abhishek.attendance.settings.SettingsService;
import com.abhishek.attendance.web.ConflictException;
import com.abhishek.attendance.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LeaveService implements ApprovedLeaveSource {

    public record Balance(long leaveTypeId, String leaveType, boolean paid, BigDecimal entitled, BigDecimal used,
                          BigDecimal pending, BigDecimal remaining) {
    }

    private final LeaveRepository repo;
    private final EmployeeService employees;
    private final AccessPolicy access;
    private final ScheduleService schedules;
    private final AttendanceCalculator calculator;
    private final SettingsService settings;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public LeaveService(LeaveRepository repo, EmployeeService employees, AccessPolicy access, ScheduleService schedules,
                        @Lazy AttendanceCalculator calculator, SettingsService settings, AuditService audit,
                        ApplicationEventPublisher events) {
        this.repo = repo;
        this.employees = employees;
        this.access = access;
        this.schedules = schedules;
        this.calculator = calculator;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
    }

    // ---------------------------------------------------------------- types

    public List<LeaveRepository.LeaveType> types() {
        return repo.types();
    }

    @Transactional
    public LeaveRepository.LeaveType saveType(Long id, String name, boolean paid, BigDecimal quota, boolean active) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required.");
        try {
            if (id == null) {
                id = repo.insertType(name.trim(), paid, quota);
                audit.record("LEAVE_TYPE_CREATED", "leave_type", id, describe(name, paid, quota));
            } else {
                LeaveRepository.LeaveType before = type(id);
                repo.updateType(id, name.trim(), paid, quota, active);
                audit.record("LEAVE_TYPE_UPDATED", "leave_type", id,
                        describe(before.name(), before.paid(), before.annualQuota()) + " -> " + describe(name, paid, quota)
                                + (before.active() != active ? (active ? ", reactivated" : ", retired") : ""));
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A leave type named " + name.trim() + " already exists.");
        }
        return type(id);
    }

    private LeaveRepository.LeaveType type(long id) {
        return repo.type(id).orElseThrow(() -> new NotFoundException("Leave type", id));
    }

    private static String describe(String name, boolean paid, BigDecimal quota) {
        return "%s (%s, %s)".formatted(name.trim(), paid ? "paid" : "unpaid", quota == null ? "no limit" : quota + " days/year");
    }

    // ---------------------------------------------------------------- balances

    public List<Balance> balances(AppUser user, long employeeId, int year) {
        access.checkCanView(user, employeeId);
        employees.get(employeeId);
        List<Balance> out = new ArrayList<>();
        for (LeaveRepository.LeaveType t : repo.types()) {
            BigDecimal used = repo.daysTaken(employeeId, t.id(), year, Set.of("APPROVED"), null);
            BigDecimal pending = repo.daysTaken(employeeId, t.id(), year, Set.of("PENDING"), null);
            if (!t.active() && used.signum() == 0 && pending.signum() == 0) continue;
            BigDecimal entitled = entitlement(employeeId, t, year);
            BigDecimal remaining = entitled == null ? null : entitled.subtract(used).subtract(pending);
            out.add(new Balance(t.id(), t.name(), t.paid(), entitled, used, pending, remaining));
        }
        return out;
    }

    private BigDecimal entitlement(long employeeId, LeaveRepository.LeaveType t, int year) {
        return repo.allocation(employeeId, t.id(), year).orElse(t.annualQuota());
    }

    @Transactional
    public void setAllocation(long employeeId, long typeId, int year, BigDecimal days) {
        Employee e = employees.get(employeeId);
        LeaveRepository.LeaveType t = type(typeId);
        repo.setAllocation(employeeId, typeId, year, days);
        audit.record("LEAVE_ALLOCATION_SET", "employee", employeeId,
                "%s %d for %s: %s".formatted(t.name(), year, e.fullName(), days == null ? "back to default" : days + " days"));
    }

    // ---------------------------------------------------------------- requests

    public List<LeaveRequest> list(AppUser user, String scope, String status, LocalDate from, LocalDate to) {
        Collection<Long> ids;
        switch (scope == null ? "mine" : scope) {
            case "mine" -> ids = access.employeeOf(user).map(e -> List.of(e.id())).orElse(List.of());
            case "approvals" -> {
                if (user.hasRole(Role.ADMIN, Role.HR)) {
                    ids = null;
                } else if (user.role() == Role.MANAGER) {
                    ids = team(user);
                } else {
                    ids = List.of();
                }
                if (status == null) status = "PENDING";
            }
            case "all" -> ids = AccessPolicy.isStaff(user) ? null : team(user);
            default -> throw new IllegalArgumentException("scope must be mine, approvals or all");
        }
        List<LeaveRequest> rows = repo.search(new LeaveRepository.Filter(ids, status, from, to, 500));
        LocalDate today = settings.today();
        return rows.stream()
                .filter(r -> !"approvals".equals(scope) || access.canApprove(user, r.employeeId()))
                .map(r -> r.withPermissions(
                        "PENDING".equals(r.status()) && access.canApprove(user, r.employeeId()),
                        canCancel(user, r, today)))
                .toList();
    }

    private List<Long> team(AppUser user) {
        var filter = access.scope(user, new EmployeeRepository.Filter(null, null, null, null, null));
        return employees.search(filter).stream().map(Employee::id).toList();
    }

    @Transactional
    public LeaveRequest apply(AppUser user, Long employeeId, long typeId, LocalDate from, LocalDate to, String reason) {
        Employee e;
        if (employeeId == null) {
            e = access.employeeOf(user).orElseThrow(() -> new ConflictException(
                    "Your account is not linked to an employee profile. Ask HR to link it."));
        } else {
            e = employees.get(employeeId);
            if (!AccessPolicy.isStaff(user) && access.employeeOf(user).map(me -> me.id() != employeeId.longValue()).orElse(true)) {
                throw new AccessDeniedException("Only HR can apply for leave on someone else's behalf");
            }
        }
        if (!e.active()) throw new ConflictException(e.fullName() + " is deactivated.");
        LeaveRepository.LeaveType t = type(typeId);
        if (!t.active()) throw new ConflictException(t.name() + " is no longer offered.");
        validateRange(from, to);
        if (repo.overlaps(e.id(), from, to, null)) {
            throw new ConflictException("There is already a pending or approved leave request in these dates.");
        }
        BigDecimal days = workingDays(e, from, to);
        if (days.signum() == 0) {
            throw new IllegalArgumentException("Those dates are all holidays or weekly offs: no leave is needed.");
        }
        checkBalance(e.id(), t, from.getYear(), days, null);
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        long id = repo.insert(e.id(), t.id(), from, to, days, cleanReason, user.id());
        audit.record("LEAVE_REQUESTED", "leave", id, "%s: %s %s to %s (%s days)".formatted(e.fullName(), t.name(), from, to, days.stripTrailingZeros().toPlainString()));
        events.publishEvent(new Events.LeaveRequested(id, e.id()));
        return get(user, id);
    }

    @Transactional
    public LeaveRequest decide(AppUser user, long id, boolean approve, String note) {
        LeaveRequest r = repo.find(id).orElseThrow(() -> new NotFoundException("Leave request", id));
        access.checkCanApprove(user, r.employeeId());
        if (!"PENDING".equals(r.status())) {
            throw new ConflictException("This request was already " + r.status().toLowerCase() + ".");
        }
        if (approve) {
            if (repo.overlaps(r.employeeId(), r.startDate(), r.endDate(), r.id())) {
                throw new ConflictException("Another approved or pending request overlaps these dates.");
            }
            checkBalance(r.employeeId(), type(r.leaveTypeId()), r.startDate().getYear(), r.days(), r.id());
        }
        String status = approve ? "APPROVED" : "REJECTED";
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (!repo.decide(id, "PENDING", status, user.id(), cleanNote)) {
            throw new ConflictException("This request was decided by someone else just now.");
        }
        audit.record(approve ? "LEAVE_APPROVED" : "LEAVE_REJECTED", "leave", id,
                "%s: %s %s to %s%s".formatted(r.employeeName(), r.leaveTypeName(), r.startDate(), r.endDate(),
                        cleanNote == null ? "" : " (" + cleanNote + ")"));
        events.publishEvent(new Events.LeaveDecided(id, r.employeeId(), status));
        return get(user, id);
    }

    @Transactional
    public LeaveRequest cancel(AppUser user, long id) {
        LeaveRequest r = repo.find(id).orElseThrow(() -> new NotFoundException("Leave request", id));
        access.checkCanView(user, r.employeeId());
        if (!canCancel(user, r, settings.today())) {
            throw new ConflictException("This request can no longer be cancelled. Ask HR.");
        }
        if (!repo.decide(id, r.status(), "CANCELLED", user.id(), "Cancelled")) {
            throw new ConflictException("This request changed just now; reload and try again.");
        }
        audit.record("LEAVE_CANCELLED", "leave", id, "%s: %s %s to %s (was %s)".formatted(r.employeeName(),
                r.leaveTypeName(), r.startDate(), r.endDate(), r.status().toLowerCase()));
        events.publishEvent(new Events.LeaveDecided(id, r.employeeId(), "CANCELLED"));
        return get(user, id);
    }

    public LeaveRequest get(AppUser user, long id) {
        LeaveRequest r = repo.find(id).orElseThrow(() -> new NotFoundException("Leave request", id));
        access.checkCanView(user, r.employeeId());
        return r.withPermissions("PENDING".equals(r.status()) && access.canApprove(user, r.employeeId()),
                canCancel(user, r, settings.today()));
    }

    /** Employees cancel their own pending requests, or approved ones that have not started. HR can cancel any open one. */
    private boolean canCancel(AppUser user, LeaveRequest r, LocalDate today) {
        if (!"PENDING".equals(r.status()) && !"APPROVED".equals(r.status())) return false;
        if (user.hasRole(Role.ADMIN, Role.HR)) return true;
        boolean own = access.employeeOf(user).map(e -> e.id().equals(r.employeeId())).orElse(false);
        return own && ("PENDING".equals(r.status()) || r.startDate().isAfter(today));
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("Start and end dates are required.");
        if (to.isBefore(from)) throw new IllegalArgumentException("The end date is before the start date.");
        if (from.getYear() != to.getYear()) {
            throw new IllegalArgumentException("Leave cannot span two calendar years; make one request per year.");
        }
        if (from.plusDays(90).isBefore(to)) throw new IllegalArgumentException("A single request can cover at most 90 days.");
    }

    private void checkBalance(long employeeId, LeaveRepository.LeaveType t, int year, BigDecimal days, Long excludeId) {
        BigDecimal entitled = entitlement(employeeId, t, year);
        if (entitled == null) return;
        BigDecimal taken = repo.daysTaken(employeeId, t.id(), year, Set.of("APPROVED", "PENDING"), excludeId);
        BigDecimal left = entitled.subtract(taken);
        if (days.compareTo(left) > 0) {
            throw new ConflictException("Not enough %s left in %d: %s day(s) requested, %s available.".formatted(
                    t.name(), year, days.stripTrailingZeros().toPlainString(), left.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()));
        }
    }

    public BigDecimal previewDays(AppUser user, Long employeeId, LocalDate from, LocalDate to) {
        Employee e = employeeId == null
                ? access.employeeOf(user).orElseThrow(() -> new ConflictException("Your account is not linked to an employee profile."))
                : employees.get(employeeId);
        access.checkCanView(user, e.id());
        if (from == null || to == null || to.isBefore(from) || from.plusDays(366).isBefore(to)) return BigDecimal.ZERO;
        return workingDays(e, from, to);
    }

    /** Working days in the range for this employee: excludes weekly offs, holidays and days outside employment. */
    public BigDecimal workingDays(Employee e, LocalDate from, LocalDate to) {
        WorkSchedule s = calculator.scheduleOf(e);
        Set<LocalDate> holidays = schedules.holidays(from, to).stream().map(ScheduleService.Holiday::holidayDate)
                .collect(Collectors.toSet());
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (e.joinedOn() != null && d.isBefore(e.joinedOn())) continue;
            if (e.leftOn() != null && d.isAfter(e.leftOn())) continue;
            if (s.weeklyOffDays().contains(d.getDayOfWeek()) || holidays.contains(d)) continue;
            n++;
        }
        return BigDecimal.valueOf(n);
    }

    // ---------------------------------------------------------------- engine input

    @Override
    public Map<Long, Map<LocalDate, AttendanceEngine.LeaveDay>> approvedLeaves(Collection<Long> employeeIds,
                                                                              LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, AttendanceEngine.LeaveDay>> out = new HashMap<>();
        for (LeaveRequest r : repo.approvedOverlapping(employeeIds, from, to)) {
            AttendanceEngine.LeaveDay day = new AttendanceEngine.LeaveDay(r.leaveTypeName(), r.paid());
            Map<LocalDate, AttendanceEngine.LeaveDay> byDate = out.computeIfAbsent(r.employeeId(), k -> new HashMap<>());
            LocalDate start = r.startDate().isBefore(from) ? from : r.startDate();
            LocalDate end = r.endDate().isAfter(to) ? to : r.endDate();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                byDate.put(d, day);
            }
        }
        return out;
    }
}
