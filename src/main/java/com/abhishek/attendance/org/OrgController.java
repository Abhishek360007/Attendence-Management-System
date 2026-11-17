package com.abhishek.attendance.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/** Departments, work schedules and holidays. Everyone signed in can read them; HR and admins change them. */
@RestController
@RequestMapping("/api")
public class OrgController {

    public record DepartmentInput(@NotBlank @Size(max = 80) String name, Long workScheduleId, Long managerEmployeeId) {
    }

    public record ScheduleInput(@NotBlank @Size(max = 80) String name, @NotNull LocalTime startTime,
                                @NotNull LocalTime endTime, @Min(0) @Max(240) int lateGraceMinutes,
                                @Min(0) @Max(240) int earlyGraceMinutes, @Min(0) @Max(600) int overtimeMinMinutes,
                                @Min(0) @Max(1440) int halfDayMinutes, Set<DayOfWeek> weeklyOffs, boolean isDefault) {
        ScheduleService.ScheduleInput toInput() {
            return new ScheduleService.ScheduleInput(name, startTime, endTime, lateGraceMinutes, earlyGraceMinutes,
                    overtimeMinMinutes, halfDayMinutes, weeklyOffs == null ? Set.of() : weeklyOffs, isDefault);
        }
    }

    public record HolidayInput(@NotNull LocalDate holidayDate, @NotBlank @Size(max = 120) String name) {
    }

    private final EmployeeService employees;
    private final ScheduleService schedules;

    public OrgController(EmployeeService employees, ScheduleService schedules) {
        this.employees = employees;
        this.schedules = schedules;
    }

    // ---------------------------------------------------------------- departments

    @GetMapping("/departments")
    public List<Department> departments() {
        return employees.departments();
    }

    @PostMapping("/departments")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public Department createDepartment(@Valid @RequestBody DepartmentInput in) {
        return employees.saveDepartment(null, in.name(), in.workScheduleId(), in.managerEmployeeId());
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public Department updateDepartment(@PathVariable long id, @Valid @RequestBody DepartmentInput in) {
        return employees.saveDepartment(id, in.name(), in.workScheduleId(), in.managerEmployeeId());
    }

    @DeleteMapping("/departments/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public ResponseEntity<Void> deleteDepartment(@PathVariable long id) {
        employees.deleteDepartment(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- schedules

    @GetMapping("/schedules")
    public List<WorkSchedule> schedules() {
        return schedules.list();
    }

    @PostMapping("/schedules")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public WorkSchedule createSchedule(@Valid @RequestBody ScheduleInput in) {
        return schedules.create(in.toInput());
    }

    @PutMapping("/schedules/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public WorkSchedule updateSchedule(@PathVariable long id, @Valid @RequestBody ScheduleInput in) {
        return schedules.update(id, in.toInput());
    }

    @DeleteMapping("/schedules/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public ResponseEntity<Void> deleteSchedule(@PathVariable long id) {
        schedules.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- holidays

    @GetMapping("/holidays")
    public List<ScheduleService.Holiday> holidays(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from == null || to == null) {
            int y = year != null ? year : LocalDate.now().getYear();
            from = LocalDate.of(y, 1, 1);
            to = LocalDate.of(y, 12, 31);
        }
        return schedules.holidays(from, to);
    }

    @PostMapping("/holidays")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public ScheduleService.Holiday addHoliday(@Valid @RequestBody HolidayInput in) {
        return schedules.addHoliday(in.holidayDate(), in.name());
    }

    @DeleteMapping("/holidays/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    public ResponseEntity<Void> deleteHoliday(@PathVariable long id) {
        schedules.deleteHoliday(id);
        return ResponseEntity.noContent().build();
    }
}
