package com.abhishek.attendance.correction;

import com.abhishek.attendance.Browser;
import com.abhishek.attendance.IntegrationTest;
import com.abhishek.attendance.auth.Role;
import com.abhishek.attendance.auth.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrectionIntegrationTest extends IntegrationTest {

    @Autowired
    UserService users;

    Browser hr, manager, employee;
    long asha, rahul;

    @BeforeEach
    void company() throws Exception {
        users.create("hr@example.com", "HR", "hr-pass-123", Role.HR);
        hr = browser().login("hr@example.com", "hr-pass-123");
        long sales = id(hr.post("/api/departments", "{\"name\":\"Sales\"}"));
        asha = id(hr.post("/api/employees", "{\"employeeCode\":\"E1\",\"deviceUserId\":\"101\",\"fullName\":\"Asha\",\"departmentId\":%d}".formatted(sales)));
        rahul = id(hr.post("/api/employees", "{\"employeeCode\":\"E2\",\"deviceUserId\":\"102\",\"fullName\":\"Rahul\",\"departmentId\":%d}".formatted(sales)));
        hr.put("/api/departments/{id}", "{\"name\":\"Sales\",\"managerEmployeeId\":%d}".formatted(asha), sales);
        hr.post("/api/employees/{id}/login", "{\"email\":\"asha@x.com\",\"password\":\"asha-pass-1\",\"role\":\"MANAGER\"}", asha);
        hr.post("/api/employees/{id}/login", "{\"email\":\"rahul@x.com\",\"password\":\"rahul-pass-1\"}", rahul);
        manager = browser().login("asha@x.com", "asha-pass-1");
        employee = browser().login("rahul@x.com", "rahul-pass-1");
        // Rahul punched in at 09:00 and a stray 09:01, then forgot to punch out.
        jdbc.update("INSERT INTO attendance_record (employee_id, check_time, source_id) VALUES ('102', '2026-11-02 09:00', 1), ('102', '2026-11-02 09:01', 2)");
    }

    @Test
    void addAMissingPunchThroughApproval() throws Exception {
        String add = "{\"action\":\"ADD\",\"requestedTime\":\"2026-11-02T18:05\",\"reason\":\"Forgot to punch out\"}";
        long c = id(employee.post("/api/corrections", add).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.canDecide").value(false))
                .andExpect(jsonPath("$.canCancel").value(true)));
        employee.post("/api/corrections/{id}/approve", null, c).andExpect(status().isForbidden());

        manager.get("/api/corrections?scope=approvals").andExpect(jsonPath("$", hasSize(1)));
        manager.post("/api/corrections/{id}/approve", null, c)
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.appliedRecordId").isNumber());

        hr.get("/api/employees/{id}/attendance?from=2026-11-02&to=2026-11-02", rahul)
                .andExpect(jsonPath("$.days[0].lastOut").value("2026-11-02T18:05:00"))
                .andExpect(jsonPath("$.days[0].missingOut").value(false));
        employee.get("/api/employees/{id}/punches?from=2026-11-02&to=2026-11-02", rahul)
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[2].source").value("MANUAL"));
    }

    @Test
    void modifyKeepsTheOriginalAndRemoveVoidsIt() throws Exception {
        long first = recordId("2026-11-02 09:00");
        long stray = recordId("2026-11-02 09:01");
        long m = id(employee.post("/api/corrections", """
                {"action":"MODIFY","attendanceRecordId":%d,"requestedTime":"2026-11-02T08:50","reason":"Device clock was slow"}"""
                .formatted(first)).andExpect(jsonPath("$.originalTime").value("2026-11-02T09:00:00")));
        employee.post("/api/corrections", "{\"action\":\"REMOVE\",\"attendanceRecordId\":%d,\"requestedTime\":\"2026-11-02T08:00\",\"reason\":\"x\"}"
                .formatted(stray)).andExpect(status().isBadRequest());
        long r = id(employee.post("/api/corrections", "{\"action\":\"REMOVE\",\"attendanceRecordId\":%d,\"reason\":\"Double punch\"}".formatted(stray)));
        employee.post("/api/corrections", "{\"action\":\"REMOVE\",\"attendanceRecordId\":%d,\"reason\":\"again\"}".formatted(stray))
                .andExpect(status().isConflict());

        hr.post("/api/corrections/{id}/approve", null, m).andExpect(status().isOk());
        hr.post("/api/corrections/{id}/approve", "{\"note\":\"ok\"}", r).andExpect(status().isOk());

        employee.get("/api/employees/{id}/punches?from=2026-11-02&to=2026-11-02", rahul)
                .andExpect(jsonPath("$[?(@.voided == true)]", hasSize(2)))
                .andExpect(jsonPath("$[?(@.voided == false)].checkTime").value("2026-11-02T08:50:00"));
        hr.get("/api/employees/{id}/attendance?from=2026-11-02&to=2026-11-02", rahul)
                .andExpect(jsonPath("$.days[0].firstIn").value("2026-11-02T08:50:00"))
                .andExpect(jsonPath("$.days[0].punches").value(1));
        hr.get("/api/audit?entityType=punch&entityId=" + first)
                .andExpect(jsonPath("$[0].action").value("PUNCH_MODIFY"))
                .andExpect(jsonPath("$[0].details").value(org.hamcrest.Matchers.containsString("2026-11-02 09:00 to 2026-11-02 08:50")));

        // The sync cursor only looks at device punches.
        Long maxDevice = jdbc.queryForObject("SELECT MAX(source_id) FROM attendance_record WHERE source = 'DEVICE'", Long.class);
        assertThat(maxDevice).isEqualTo(2L);
    }

    @Test
    void whoMayRequestAndApprove() throws Exception {
        // HR enters and approves a correction for someone else in one step...
        hr.post("/api/corrections", "{\"employeeId\":%d,\"action\":\"ADD\",\"requestedTime\":\"2026-11-02T18:00\",\"reason\":\"Card reader down\",\"approveNow\":true}"
                .formatted(rahul)).andExpect(jsonPath("$.status").value("APPROVED"));
        // ...but a manager cannot approve their own correction, and an employee cannot file one for a colleague.
        manager.post("/api/corrections", "{\"action\":\"ADD\",\"requestedTime\":\"2026-11-02T09:00\",\"reason\":\"x\",\"approveNow\":true}")
                .andExpect(status().isForbidden());
        employee.post("/api/corrections", "{\"employeeId\":%d,\"action\":\"ADD\",\"requestedTime\":\"2026-11-02T09:00\",\"reason\":\"x\"}".formatted(asha))
                .andExpect(status().isForbidden());
        employee.post("/api/corrections", "{\"action\":\"ADD\",\"requestedTime\":\"2099-01-01T09:00\",\"reason\":\"x\"}")
                .andExpect(status().isBadRequest());

        long c = id(employee.post("/api/corrections", "{\"action\":\"ADD\",\"requestedTime\":\"2026-11-03T09:00\",\"reason\":\"x\"}"));
        employee.post("/api/corrections/{id}/cancel", null, c).andExpect(jsonPath("$.status").value("CANCELLED"));
        manager.post("/api/corrections/{id}/approve", null, c).andExpect(status().isConflict());
    }

    private long recordId(String time) {
        return jdbc.queryForObject("SELECT id FROM attendance_record WHERE check_time = ?::timestamp", Long.class, time);
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
