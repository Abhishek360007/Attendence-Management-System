package com.abhishek.attendance.correction;

import com.abhishek.attendance.auth.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN', 'HR', 'MANAGER', 'EMPLOYEE')")
public class CorrectionController {

    public record RequestInput(Long employeeId, @NotNull CorrectionService.Action action, Long attendanceRecordId,
                               LocalDateTime requestedTime, @NotBlank @Size(max = 500) String reason,
                               Boolean approveNow) {
    }

    public record DecisionInput(@Size(max = 500) String note) {
    }

    private final CorrectionService service;

    public CorrectionController(CorrectionService service) {
        this.service = service;
    }

    @GetMapping("/employees/{id}/punches")
    public List<CorrectionService.Punch> punches(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.plusDays(62).isBefore(to)) throw new IllegalArgumentException("At most 62 days at a time.");
        return service.punches(user, id, from, to);
    }

    @GetMapping("/corrections")
    public List<CorrectionService.Correction> list(@AuthenticationPrincipal AppUser user,
                                                   @RequestParam(required = false) String scope,
                                                   @RequestParam(required = false) String status) {
        return service.list(user, scope, status);
    }

    @GetMapping("/corrections/{id}")
    public CorrectionService.Correction get(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return service.get(user, id);
    }

    @PostMapping("/corrections")
    public CorrectionService.Correction request(@AuthenticationPrincipal AppUser user, @Valid @RequestBody RequestInput in) {
        return service.request(user, in.employeeId(), in.action(), in.attendanceRecordId(), in.requestedTime(),
                in.reason(), Boolean.TRUE.equals(in.approveNow()));
    }

    @PostMapping("/corrections/{id}/approve")
    public CorrectionService.Correction approve(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                                @Valid @RequestBody(required = false) DecisionInput in) {
        return service.decide(user, id, true, in == null ? null : in.note());
    }

    @PostMapping("/corrections/{id}/reject")
    public CorrectionService.Correction reject(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                               @Valid @RequestBody(required = false) DecisionInput in) {
        return service.decide(user, id, false, in == null ? null : in.note());
    }

    @PostMapping("/corrections/{id}/cancel")
    public CorrectionService.Correction cancel(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return service.cancel(user, id);
    }
}
