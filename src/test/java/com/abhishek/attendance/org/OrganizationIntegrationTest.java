package com.abhishek.attendance.org;

import com.abhishek.attendance.Browser;
import com.abhishek.attendance.IntegrationTest;
import com.abhishek.attendance.auth.Role;
import com.abhishek.attendance.auth.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrganizationIntegrationTest extends IntegrationTest {

    @Autowired
    UserService users;

    Browser hr;
    long sales;
    long asha;   // manager of the sales department
    long rahul;  // in sales
    long neha;   // in another department

    @BeforeEach
    void company() throws Exception {
        users.create("hr@example.com", "HR", "hr-pass-123", Role.HR);
        hr = browser().login("hr@example.com", "hr-pass-123");
        jdbc.update("UPDATE company_setting SET value = 'UTC' WHERE key = 'timezone'");
        settingsService.evictCache();

        sales = id(hr.post("/api/departments", "{\"name\":\"Sales\"}").andExpect(status().isOk()));
        long ops = id(hr.post("/api/departments", "{\"name\":\"Operations\"}"));
        asha = id(hr.post("/api/employees", employee("E001", "101", "Asha Verma", sales, null, "50000")));
        rahul = id(hr.post("/api/employees", employee("E002", "102", "Rahul Singh", sales, null, "30000")));
        neha = id(hr.post("/api/employees", employee("E003", "103", "Neha Gupta", ops, null, "40000")));
        hr.put("/api/departments/{id}", "{\"name\":\"Sales\",\"managerEmployeeId\":%d}".formatted(asha), sales)
                .andExpect(jsonPath("$.managerName").value("Asha Verma"));

        hr.post("/api/employees/{id}/login", "{\"email\":\"asha@example.com\",\"password\":\"asha-pass-1\",\"role\":\"MANAGER\"}", asha)
                .andExpect(jsonPath("$.userEmail").value("asha@example.com"));
        hr.post("/api/employees/{id}/login", "{\"email\":\"rahul@example.com\",\"password\":\"rahul-pass-1\"}", rahul)
                .andExpect(jsonPath("$.userRole").value("EMPLOYEE"));
    }

    @Test
    void validationAndDuplicates() throws Exception {
        hr.post("/api/employees", employee("e001", null, "Someone", null, null, null)).andExpect(status().isConflict());
        hr.post("/api/employees", employee("E009", "101", "Someone", null, null, null)).andExpect(status().isConflict());
        hr.post("/api/departments", "{\"name\":\"sales\"}").andExpect(status().isConflict());
        hr.put("/api/employees/{id}", employee("E001", "101", "Asha Verma", sales, asha, "50000"), asha)
                .andExpect(status().isBadRequest());
        // Rahul reports to Asha, so Asha cannot report to Rahul.
        hr.put("/api/employees/{id}", employee("E002", "102", "Rahul Singh", sales, asha, "30000"), rahul).andExpect(status().isOk());
        hr.put("/api/employees/{id}", employee("E001", "101", "Asha Verma", sales, rahul, "50000"), asha)
                .andExpect(status().isBadRequest());
        // HR cannot hand out admin access.
        hr.post("/api/employees/{id}/login", "{\"email\":\"n@example.com\",\"password\":\"neha-pass-1\",\"role\":\"ADMIN\"}", neha)
                .andExpect(status().isForbidden());
    }

    @Test
    void managersSeeTheirTeamAndEmployeesOnlyThemselves() throws Exception {
        Browser manager = browser().login("asha@example.com", "asha-pass-1");
        manager.get("/api/employees").andExpect(jsonPath("$[*].fullName", containsInAnyOrder("Asha Verma", "Rahul Singh")))
                .andExpect(jsonPath("$[0].monthlySalary").doesNotExist());
        manager.get("/api/employees/{id}", neha).andExpect(status().isForbidden());
        manager.get("/api/employees/{id}/attendance", rahul).andExpect(status().isOk());
        manager.post("/api/employees", employee("E010", null, "X", null, null, null)).andExpect(status().isForbidden());

        Browser employee = browser().login("rahul@example.com", "rahul-pass-1");
        employee.get("/api/employees").andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].fullName").value("Rahul Singh"));
        employee.get("/api/employees/me").andExpect(jsonPath("$.employeeCode").value("E002"));
        employee.get("/api/employees/{id}/attendance", asha).andExpect(status().isForbidden());
        employee.get("/api/attendance/register").andExpect(status().isForbidden());

        hr.get("/api/employees/{id}", rahul).andExpect(jsonPath("$.monthlySalary").value(30000));
    }

    @Test
    void attendanceIsCalculatedFromMappedPunchesSchedulesAndHolidays() throws Exception {
        LocalDate mon = LocalDate.of(2026, 11, 2);
        punch("101", mon.atTime(9, 25).toString(), 1);
        punch("101", mon.atTime(18, 0).toString(), 2);
        punch("102", mon.atTime(8, 58).toString(), 3);
        punch("102", mon.atTime(19, 40).toString(), 4);
        punch("999", mon.atTime(9, 0).toString(), 5);       // unknown device user
        hr.post("/api/holidays", "{\"holidayDate\":\"2026-11-03\",\"name\":\"Founders day\"}").andExpect(status().isOk());
        hr.post("/api/holidays", "{\"holidayDate\":\"2026-11-03\",\"name\":\"Dup\"}").andExpect(status().isConflict());

        hr.get("/api/attendance/register?date=2026-11-02")
                .andExpect(jsonPath("$[?(@.employee.fullName == 'Asha Verma')].day.lateMinutes").value(25))
                .andExpect(jsonPath("$[?(@.employee.fullName == 'Rahul Singh')].day.overtimeMinutes").value(102))
                .andExpect(jsonPath("$[?(@.employee.fullName == 'Neha Gupta')].day.status").value("ABSENT"));

        hr.get("/api/employees/{id}/attendance?from=2026-11-01&to=2026-11-04", asha)
                .andExpect(jsonPath("$.days[*].status").value(
                        org.hamcrest.Matchers.contains("WEEKLY_OFF", "PRESENT", "HOLIDAY", "ABSENT")))
                .andExpect(jsonPath("$.days[2].note").value("Founders day"))
                .andExpect(jsonPath("$.summary.lateDays").value(1))
                .andExpect(jsonPath("$.summary.attendancePercent").value(50.0))
                .andExpect(jsonPath("$.schedule.name").value("General shift"));

        hr.get("/api/employees/{id}/attendance?month=2026-11", asha).andExpect(jsonPath("$.days", hasSize(30)));

        hr.get("/api/employees/unmapped-device-ids")
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].deviceUserId").value("999"));

        hr.get("/api/reports/attendance-summary?from=2026-11-02&to=2026-11-02&departmentId=" + sales)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].employee.fullName").value("Asha Verma"));
    }

    @Test
    void departmentScheduleAppliesUnlessTheEmployeeOverridesIt() throws Exception {
        long early = id(hr.post("/api/schedules", """
                {"name":"Early","startTime":"07:00","endTime":"15:00","lateGraceMinutes":0,"earlyGraceMinutes":0,
                 "overtimeMinMinutes":30,"halfDayMinutes":0,"weeklyOffs":["SATURDAY","SUNDAY"],"isDefault":false}"""));
        hr.put("/api/departments/{id}", "{\"name\":\"Sales\",\"workScheduleId\":%d,\"managerEmployeeId\":%d}".formatted(early, asha), sales);
        punch("102", "2026-11-02T07:05", 1);
        punch("102", "2026-11-02T15:00", 2);

        hr.get("/api/employees/{id}/attendance?from=2026-11-02&to=2026-11-02", rahul)
                .andExpect(jsonPath("$.schedule.name").value("Early"))
                .andExpect(jsonPath("$.days[0].lateMinutes").value(5));
        hr.get("/api/employees/{id}/attendance?from=2026-11-07&to=2026-11-07", rahul)
                .andExpect(jsonPath("$.days[0].status").value("WEEKLY_OFF"));
        hr.delete("/api/schedules/{id}", early).andExpect(status().isConflict());
        hr.get("/api/audit?entityType=department&entityId=" + sales).andExpect(jsonPath("$[0].action").value("DEPARTMENT_UPDATED"));
    }

    @Test
    void deactivatingAnEmployeeBlocksTheirLogin() throws Exception {
        hr.put("/api/employees/{id}", """
                {"employeeCode":"E002","deviceUserId":"102","fullName":"Rahul Singh","departmentId":%d,"active":false}"""
                .formatted(sales), rahul).andExpect(jsonPath("$.active").value(false));
        browser().post("/api/auth/login", "{\"email\":\"rahul@example.com\",\"password\":\"rahul-pass-1\"}")
                .andExpect(status().isUnauthorized());
        hr.get("/api/audit?entityType=employee&entityId=" + rahul)
                .andExpect(jsonPath("$[0].action").value("EMPLOYEE_DEACTIVATED"));
    }

    private void punch(String deviceUser, String time, long sourceId) {
        jdbc.update("INSERT INTO attendance_record (employee_id, employee_name, check_time, check_type, device_id, source_id) "
                + "VALUES (?, 'from device', ?::timestamp, 'I', 1, ?)", deviceUser, time.replace('T', ' '), sourceId);
    }

    private static String employee(String code, String device, String name, Long dept, Long manager, String salary) {
        return """
                {"employeeCode":"%s","deviceUserId":%s,"fullName":"%s","departmentId":%s,"managerId":%s,"monthlySalary":%s,
                 "designation":"Executive","joinedOn":"2025-01-01"}"""
                .formatted(code, device == null ? "null" : "\"" + device + "\"", name, dept, manager, salary);
    }

    private static long id(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
