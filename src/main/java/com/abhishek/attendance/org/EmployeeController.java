package com.abhishek.attendance.org;

import com.abhishek.attendance.auth.AppUser;
import com.abhishek.attendance.auth.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/employees")
public class EmployeeController {

    public record EmployeeInput(
            @NotBlank @Size(max = 32) String employeeCode,
            @Size(max = 64) String deviceUserId,
            @NotBlank @Size(max = 128) String fullName,
            @Email @Size(max = 160) String email,
            @Size(max = 32) String phone,
            Long departmentId,
            @Size(max = 80) String designation,
            Long managerId,
            Long workScheduleId,
            @DecimalMin("0") BigDecimal monthlySalary,
            LocalDate joinedOn,
            LocalDate leftOn,
            Boolean active) {

        EmployeeRepository.Fields fields() {
            return new EmployeeRepository.Fields(employeeCode, deviceUserId, fullName, email, phone, departmentId,
                    designation, managerId, workScheduleId, monthlySalary, joinedOn, leftOn, active == null || active);
        }
    }

    public record LoginInput(@NotBlank @Email String email, @NotBlank String password, Role role) {
    }

    private final EmployeeService service;
    private final AccessPolicy access;

    public EmployeeController(EmployeeService service, AccessPolicy access) {
        this.service = service;
        this.access = access;
    }

    /** Everyone may call this; results are limited to the employees the caller may see. */
    @GetMapping
    public List<Employee> list(@AuthenticationPrincipal AppUser user,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) Long departmentId,
                               @RequestParam(required = false) Boolean active) {
        var filter = access.scope(user, new EmployeeRepository.Filter(q, departmentId, active, null, null));
        return service.search(filter).stream().map(e -> visible(user, e)).toList();
    }

    @GetMapping("/{id}")
    public Employee get(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        access.checkCanView(user, id);
        return visible(user, service.get(id));
    }

    @GetMapping("/me")
    public Employee me(@AuthenticationPrincipal AppUser user) {
        return access.employeeOf(user).map(e -> visible(user, e))
                .orElseThrow(() -> new com.abhishek.attendance.web.NotFoundException("Employee profile for", user == null ? "API key" : user.email()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public Employee create(@Valid @RequestBody EmployeeInput body) {
        return service.create(body.fields());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public Employee update(@PathVariable long id, @Valid @RequestBody EmployeeInput body) {
        return service.update(id, body.fields());
    }

    @PostMapping("/{id}/login")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public Employee createLogin(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                @Valid @RequestBody LoginInput body) {
        Role role = body.role() == null ? Role.EMPLOYEE : body.role();
        // Only admins hand out HR or admin access.
        if ((role == Role.ADMIN || role == Role.HR) && !user.hasRole(Role.ADMIN)) {
            throw new org.springframework.security.access.AccessDeniedException("Only admins can grant HR or admin access");
        }
        return service.createLogin(id, body.email(), body.password(), role);
    }

    /** Device user numbers with punches but no employee profile yet. */
    @GetMapping("/unmapped-device-ids")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public List<EmployeeRepository.UnmappedDeviceUser> unmapped() {
        return service.unmappedDeviceUsers();
    }

    private static Employee visible(AppUser user, Employee e) {
        return AccessPolicy.isStaff(user) ? e : e.withoutSalary();
    }
}
