package com.abhishek.attendance.notify;

import com.abhishek.attendance.Browser;
import com.abhishek.attendance.IntegrationTest;
import com.abhishek.attendance.auth.Role;
import com.abhishek.attendance.auth.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportsAndNotificationsIntegrationTest extends IntegrationTest {

    @Autowired
    UserService users;

    @Autowired
    NotificationJobs jobs;

    Browser hr, manager, employee;
    long asha, rahul, unpaid;

    @BeforeEach
    void company() throws Exception {
        users.create("hr@example.com", "HR", "hr-pass-123", Role.HR);
        hr = browser().login("hr@example.com", "hr-pass-123");
        long sales = id(hr.post("/api/departments", "{\"name\":\"Sales\"}"));
        asha = id(hr.post("/api/employees", "{\"employeeCode\":\"E1\",\"deviceUserId\":\"101\",\"fullName\":\"Asha\",\"departmentId\":%d,\"joinedOn\":\"2026-10-01\"}".formatted(sales)));
        rahul = id(hr.post("/api/employees", "{\"employeeCode\":\"E2\",\"deviceUserId\":\"102\",\"fullName\":\"Rahul\",\"departmentId\":%d,\"monthlySalary\":31000,\"joinedOn\":\"2026-10-01\"}".formatted(sales)));
        hr.put("/api/departments/{id}", "{\"name\":\"Sales\",\"managerEmployeeId\":%d}".formatted(asha), sales);
        hr.post("/api/employees/{id}/login", "{\"email\":\"asha@x.com\",\"password\":\"asha-pass-1\",\"role\":\"MANAGER\"}", asha);
        hr.post("/api/employees/{id}/login", "{\"email\":\"rahul@x.com\",\"password\":\"rahul-pass-1\"}", rahul);
        manager = browser().login("asha@x.com", "asha-pass-1");
        employee = browser().login("rahul@x.com", "rahul-pass-1");
        String types = hr.get("/api/leave/types").andReturn().getResponse().getContentAsString();
        unpaid = ((Number) JsonPath.<java.util.List<Object>>read(types, "$[?(@.name == 'Unpaid leave')].id").getFirst()).longValue();
    }

    /**
     * October 2026: 31 days, 4 Sundays, holiday on the 2nd -> 26 working days. Rahul works the 1st and the 5th
     * (3 h overtime), takes unpaid leave on the 6th and is absent the other 23 working days.
     */
    @Test
    void payrollFollowsAttendance() throws Exception {
        hr.post("/api/holidays", "{\"holidayDate\":\"2026-10-02\",\"name\":\"Gandhi Jayanti\"}");
        punch("102", "2026-10-01 09:00", 1);
        punch("102", "2026-10-01 18:00", 2);
        punch("102", "2026-10-05 08:00", 3);
        punch("102", "2026-10-05 20:00", 4);
        long leave = id(employee.post("/api/leave/requests", "{\"leaveTypeId\":%d,\"startDate\":\"2026-10-06\",\"endDate\":\"2026-10-06\"}".formatted(unpaid)));
        manager.post("/api/leave/requests/{id}/approve", null, leave).andExpect(status().isOk());

        hr.get("/api/payroll?month=2026-10")
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.overtimeMultiplier").value(1.5))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].workingDays").value(26))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].presentDays").value(2.0))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].absentDays").value(23.0))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].unpaidLeaveDays").value(1))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].overtimeMinutes").value(180))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].payableDays").value(7.0))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].basePay").value(7000.00))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].overtimePay").value(596.15))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Rahul')].grossPay").value(7596.15))
                .andExpect(jsonPath("$.rows[?(@.fullName == 'Asha')].grossPay").value(org.hamcrest.Matchers.contains((Object) null)));

        hr.get("/api/payroll/export?month=2026-10")
                .andExpect(header().string("Content-Disposition", containsString("payroll-2026-10.csv")))
                .andExpect(content().string(containsString("\"E2\",\"Rahul\",\"Sales\"")))
                .andExpect(content().string(containsString("\"7596.15\"")));
        manager.get("/api/payroll?month=2026-10").andExpect(status().isForbidden());
        employee.get("/api/payroll?month=2026-10").andExpect(status().isForbidden());
    }

    @Test
    void daysBeforeTheFirstDevicePunchAreNotAbsences() throws Exception {
        long old = id(hr.post("/api/employees", "{\"employeeCode\":\"E9\",\"deviceUserId\":\"109\",\"fullName\":\"Old Timer\",\"monthlySalary\":30000,\"joinedOn\":\"2025-01-01\"}"));
        punch("109", "2026-10-01 09:00", 1);
        punch("109", "2026-10-01 18:00", 2);

        hr.get("/api/payroll?month=2026-09").andExpect(jsonPath("$.rows[?(@.fullName == 'Old Timer')]", hasSize(0)));
        hr.get("/api/employees/{id}/attendance?from=2026-09-28&to=2026-10-02", old)
                .andExpect(jsonPath("$.days", hasSize(2)))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-01"));

        // An explicit start date wins over the first punch.
        hr.put("/api/settings", "{\"tracking_start_date\":\"2026-09-29\"}").andExpect(status().isForbidden()); // HR may not change settings
        jdbc.update("INSERT INTO company_setting (key, value) VALUES ('tracking_start_date', '2026-09-29')");
        settingsService.evictCache();
        hr.get("/api/employees/{id}/attendance?from=2026-09-28&to=2026-10-02", old)
                .andExpect(jsonPath("$.days[0].date").value("2026-09-29"))
                .andExpect(jsonPath("$.days[0].status").value("ABSENT"));
    }

    @Test
    void analyticsSummarisesTheMonth() throws Exception {
        punch("102", "2026-10-01 09:00", 1);
        punch("102", "2026-10-01 18:00", 2);
        punch("102", "2026-10-05 09:30", 3);
        punch("102", "2026-10-05 21:00", 4);
        punch("101", "2026-10-01 09:00", 5);

        hr.get("/api/analytics?month=2026-10&months=3")
                .andExpect(jsonPath("$.trend", hasSize(3)))
                .andExpect(jsonPath("$.current.employees").value(2))
                .andExpect(jsonPath("$.current.lateArrivals").value(1))
                .andExpect(jsonPath("$.current.overtimeHours").value(2.5))
                .andExpect(jsonPath("$.previous.employees").value(0))
                .andExpect(jsonPath("$.departments[0].department").value("Sales"))
                .andExpect(jsonPath("$.frequentAbsentees[0].fullName").value("Asha"))   // 1 day present vs Rahul's 2
                .andExpect(jsonPath("$.frequentAbsentees[0].absentDays").value(26.0))
                .andExpect(jsonPath("$.overtimeLeaders[0].overtimeHours").value(2.5));
        manager.get("/api/analytics?month=2026-10").andExpect(status().isOk());
        employee.get("/api/analytics?month=2026-10").andExpect(status().isForbidden());
    }

    @Test
    void leaveRequestsAndDecisionsNotifyTheRightPeople() throws Exception {
        long leave = id(employee.post("/api/leave/requests", "{\"leaveTypeId\":%d,\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-01\",\"reason\":\"Wedding\"}".formatted(unpaid)));
        awaitNotifications("asha@x.com", 1);
        manager.get("/api/notifications")
                .andExpect(jsonPath("$[0].title").value("Leave request from Rahul"))
                .andExpect(jsonPath("$[0].body").value(containsString("Reason: Wedding")))
                .andExpect(jsonPath("$[0].link").value("/leave?tab=approvals"));
        manager.get("/api/notifications/unread").andExpect(jsonPath("$.count").value(1));

        manager.post("/api/leave/requests/{id}/reject", "{\"note\":\"Busy week\"}", leave);
        awaitNotifications("rahul@x.com", 1);
        employee.get("/api/notifications")
                .andExpect(jsonPath("$[0].title").value("Your leave was rejected"))
                .andExpect(jsonPath("$[0].body").value(containsString("Note: Busy week")));
        employee.post("/api/notifications/read-all", null).andExpect(status().isNoContent());
        employee.get("/api/notifications/unread").andExpect(jsonPath("$.count").value(0));
        // HR is not bothered: the team has a manager.
        hr.get("/api/notifications").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void dailyAlertsGoToHrManagersAndLateEmployees() throws Exception {
        LocalDate mon = LocalDate.of(2026, 11, 2);
        punch("102", "2026-11-02 09:40", 1);   // Rahul late; Asha absent
        jobs.dailyAlerts(mon);
        hr.get("/api/notifications").andExpect(jsonPath("$[0].title").value(containsString("1 absent, 1 late")))
                .andExpect(jsonPath("$[0].body").value(containsString("Asha")));
        manager.get("/api/notifications").andExpect(jsonPath("$[0].title").value(containsString("Your team")));
        employee.get("/api/notifications").andExpect(jsonPath("$[0].title").value("You arrived 40 minutes late today"));

        jobs.irregularities(mon);                 // Rahul has a single punch
        hr.get("/api/notifications").andExpect(jsonPath("$[0].body").value(containsString("Rahul, Sales (missing punch)")));
    }

    @Test
    void eachJobPeriodRunsOnceAndFailuresAreRetried() {
        AtomicInteger runs = new AtomicInteger();
        jobs.run("test-job", "2026-11-02", runs::incrementAndGet);
        jobs.run("test-job", "2026-11-02", runs::incrementAndGet);
        assertThat(runs.get()).isEqualTo(1);

        jobs.run("failing", "p", () -> { throw new IllegalStateException("boom"); });
        jobs.run("failing", "p", runs::incrementAndGet);
        assertThat(runs.get()).isEqualTo(2);
    }

    private void awaitNotifications(String email, int count) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notification n JOIN app_user u ON u.id = n.user_id WHERE u.email = ?",
                    Integer.class, email);
            if (n != null && n >= count) return;
            Thread.sleep(100);
        }
        throw new AssertionError("No notification for " + email);
    }

    private void punch(String deviceUser, String time, long sourceId) {
        jdbc.update("INSERT INTO attendance_record (employee_id, check_time, source_id) VALUES (?, ?::timestamp, ?)",
                deviceUser, time, sourceId);
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
