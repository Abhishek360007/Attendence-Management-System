package com.abhishek.attendance.leave;

import com.abhishek.attendance.auth.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/leave")
@PreAuthorize("hasAnyRole('ADMIN', 'HR', 'MANAGER', 'EMPLOYEE')")
public class LeaveController {

    public record ApplyInput(Long employeeId, @NotNull Long leaveTypeId, @NotNull LocalDate startDate,
                             @NotNull LocalDate endDate, @Size(max = 500) String reason) {
    }

    public record DecisionInput(@Size(max = 500) String note) {
    }

    public record TypeInput(@NotBlank @Size(max = 60) String name, boolean paid,
                            @DecimalMin("0") BigDecimal annualQuota, Boolean active) {
    }

    public record AllocationInput(@NotNull Long employeeId, @NotNull Long leaveTypeId, int year,
                                  @DecimalMin("0") BigDecimal days) {
    }

    private final LeaveService service;

    public LeaveController(LeaveService service) {
        this.service = service;
    }

    @GetMapping("/types")
    public List<LeaveRepository.LeaveType> types() {
        return service.types();
    }

    @PostMapping("/types")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public LeaveRepository.LeaveType createType(@Valid @RequestBody TypeInput in) {
        return service.saveType(null, in.name(), in.paid(), in.annualQuota(), true);
    }

    @PutMapping("/types/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public LeaveRepository.LeaveType updateType(@PathVariable long id, @Valid @RequestBody TypeInput in) {
        return service.saveType(id, in.name(), in.paid(), in.annualQuota(), in.active() == null || in.active());
    }

    @GetMapping("/balances")
    public List<LeaveService.Balance> balances(@AuthenticationPrincipal AppUser user, @RequestParam long employeeId,
                                               @RequestParam(required = false) Integer year) {
        return service.balances(user, employeeId, year != null ? year : LocalDate.now().getYear());
    }

    @PutMapping("/allocations")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public ResponseEntity<Void> setAllocation(@Valid @RequestBody AllocationInput in) {
        service.setAllocation(in.employeeId(), in.leaveTypeId(), in.year(), in.days());
        return ResponseEntity.noContent().build();
    }

    /** scope: mine (default), approvals (pending requests the caller can decide), all (everything visible). */
    @GetMapping("/requests")
    public List<LeaveRequest> requests(@AuthenticationPrincipal AppUser user,
                                       @RequestParam(required = false) String scope,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(user, scope, status, from, to);
    }

    @GetMapping("/requests/{id}")
    public LeaveRequest get(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return service.get(user, id);
    }

    @PostMapping("/requests")
    public LeaveRequest apply(@AuthenticationPrincipal AppUser user, @Valid @RequestBody ApplyInput in) {
        return service.apply(user, in.employeeId(), in.leaveTypeId(), in.startDate(), in.endDate(), in.reason());
    }

    @PostMapping("/requests/{id}/approve")
    public LeaveRequest approve(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                @Valid @RequestBody(required = false) DecisionInput in) {
        return service.decide(user, id, true, in == null ? null : in.note());
    }

    @PostMapping("/requests/{id}/reject")
    public LeaveRequest reject(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                               @Valid @RequestBody(required = false) DecisionInput in) {
        return service.decide(user, id, false, in == null ? null : in.note());
    }

    @PostMapping("/requests/{id}/cancel")
    public LeaveRequest cancel(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return service.cancel(user, id);
    }

    /** Working days a request for these dates would use, for the application form. */
    @GetMapping("/working-days")
    public BigDecimal workingDays(@AuthenticationPrincipal AppUser user, @RequestParam(required = false) Long employeeId,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.previewDays(user, employeeId, from, to);
    }
}
