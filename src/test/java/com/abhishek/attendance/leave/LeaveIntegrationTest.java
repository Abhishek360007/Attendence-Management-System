package com.abhishek.attendance.leave;

import com.abhishek.attendance.Browser;
import com.abhishek.attendance.IntegrationTest;
import com.abhishek.attendance.auth.Role;
import com.abhishek.attendance.auth.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeaveIntegrationTest extends IntegrationTest {

    @Autowired
    UserService users;

    Browser hr, manager, employee, other;
    long casual, unpaid, rahul;

    @BeforeEach
    void company() throws Exception {
        users.create("hr@example.com", "HR", "hr-pass-123", Role.HR);
        hr = browser().login("hr@example.com", "hr-pass-123");
        long sales = id(hr.post("/api/departments", "{\"name\":\"Sales\"}"));
        long asha = id(hr.post("/api/employees", emp("E1", "101", "Asha", sales)));
        rahul = id(hr.post("/api/employees", emp("E2", "102", "Rahul", sales)));
        long neha = id(hr.post("/api/employees", emp("E3", "103", "Neha", null)));
        hr.put("/api/departments/{id}", "{\"name\":\"Sales\",\"managerEmployeeId\":%d}".formatted(asha), sales);
        hr.post("/api/employees/{id}/login", "{\"email\":\"asha@x.com\",\"password\":\"asha-pass-1\",\"role\":\"MANAGER\"}", asha);
        hr.post("/api/employees/{id}/login", "{\"email\":\"rahul@x.com\",\"password\":\"rahul-pass-1\"}", rahul);
        hr.post("/api/employees/{id}/login", "{\"email\":\"neha@x.com\",\"password\":\"neha-pass-1\"}", neha);
        manager = browser().login("asha@x.com", "asha-pass-1");
        employee = browser().login("rahul@x.com", "rahul-pass-1");
        other = browser().login("neha@x.com", "neha-pass-1");
        casual = typeId("Casual leave");
        unpaid = typeId("Unpaid leave");
        hr.post("/api/holidays", "{\"holidayDate\":\"2026-12-25\",\"name\":\"Christmas\"}");
    }

    @Test
    void applyApproveAndTheDaysBecomeLeaveInsteadOfAbsence() throws Exception {
        // Mon 21 - Sun 27 Dec: Christmas (Fri) and Sunday are not counted -> 5 days.
        employee.get("/api/leave/working-days?from=2026-12-21&to=2026-12-27").andExpect(jsonPath("$").value(5));
        long req = id(employee.post("/api/leave/requests", apply(casual, "2026-12-21", "2026-12-27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.days").value(5))
                .andExpect(jsonPath("$.canDecide").value(false))
                .andExpect(jsonPath("$.canCancel").value(true)));

        employee.post("/api/leave/requests", apply(casual, "2026-12-23", "2026-12-23")).andExpect(status().isConflict());
        employee.post("/api/leave/requests/{id}/approve", null, req).andExpect(status().isForbidden());
        other.get("/api/leave/requests/{id}", req).andExpect(status().isForbidden());
        employee.get("/api/leave/requests/{id}", req).andExpect(jsonPath("$.leaveTypeName").value("Casual leave"));

        manager.get("/api/leave/requests?scope=approvals")
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].canDecide").value(true));
        manager.post("/api/leave/requests/{id}/approve", "{\"note\":\"Enjoy\"}", req)
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByName").value("Asha"));
        hr.post("/api/leave/requests/{id}/reject", null, req).andExpect(status().isConflict());

        employee.get("/api/leave/balances?employeeId={id}&year=2026", rahul)
                .andExpect(jsonPath("$[?(@.leaveType == 'Casual leave')].used").value(5.0))
                .andExpect(jsonPath("$[?(@.leaveType == 'Casual leave')].remaining").value(7.0));

        hr.get("/api/employees/{id}/attendance?from=2026-12-21&to=2026-12-25", rahul)
                .andExpect(jsonPath("$.days[0].status").value("LEAVE"))
                .andExpect(jsonPath("$.days[0].note").value("Casual leave"))
                .andExpect(jsonPath("$.days[4].status").value("HOLIDAY"));
        hr.get("/api/audit?entityType=leave&entityId=" + req).andExpect(jsonPath("$[0].action").value("LEAVE_APPROVED"));
    }

    @Test
    void balancesAreEnforcedButUnpaidLeaveHasNoLimit() throws Exception {
        // 13 working days > 12 casual days.
        employee.post("/api/leave/requests", apply(casual, "2026-03-02", "2026-03-16")).andExpect(status().isConflict());
        employee.post("/api/leave/requests", apply(unpaid, "2026-03-02", "2026-03-16")).andExpect(status().isOk());

        // HR raises the allowance for this employee.
        hr.put("/api/leave/allocations", "{\"employeeId\":%d,\"leaveTypeId\":%d,\"year\":2026,\"days\":20}".formatted(rahul, casual))
                .andExpect(status().isNoContent());
        employee.post("/api/leave/requests", apply(casual, "2026-04-01", "2026-04-17")).andExpect(status().isOk());

        employee.post("/api/leave/requests", apply(casual, "2026-12-30", "2027-01-02")).andExpect(status().isBadRequest());
        employee.post("/api/leave/requests", apply(casual, "2026-12-27", "2026-12-27")).andExpect(status().isBadRequest()); // a Sunday
    }

    @Test
    void managersCannotApproveTheirOwnLeaveAndCancellingFreesTheDays() throws Exception {
        long own = id(manager.post("/api/leave/requests", apply(casual, "2026-12-01", "2026-12-01")));
        manager.post("/api/leave/requests/{id}/approve", null, own).andExpect(status().isForbidden());
        hr.post("/api/leave/requests/{id}/approve", null, own).andExpect(jsonPath("$.status").value("APPROVED"));

        long mine = id(employee.post("/api/leave/requests", apply(casual, "2026-12-02", "2026-12-04")));
        employee.post("/api/leave/requests/{id}/cancel", null, mine).andExpect(jsonPath("$.status").value("CANCELLED"));
        employee.post("/api/leave/requests", apply(casual, "2026-12-02", "2026-12-04")).andExpect(status().isOk());
        employee.get("/api/leave/requests").andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void hrManagesLeaveTypes() throws Exception {
        long wfh = id(hr.post("/api/leave/types", "{\"name\":\"Bereavement leave\",\"paid\":true,\"annualQuota\":3}"));
        hr.post("/api/leave/types", "{\"name\":\"bereavement LEAVE\",\"paid\":true}").andExpect(status().isConflict());
        employee.post("/api/leave/types", "{\"name\":\"Free days\",\"paid\":true}").andExpect(status().isForbidden());
        hr.put("/api/leave/types/{id}", "{\"name\":\"Bereavement leave\",\"paid\":true,\"annualQuota\":3,\"active\":false}", wfh);
        employee.post("/api/leave/requests", apply(wfh, "2026-05-04", "2026-05-04")).andExpect(status().isConflict());
    }

    private long typeId(String name) throws Exception {
        String json = hr.get("/api/leave/types").andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<java.util.List<Object>>read(json, "$[?(@.name == '" + name + "')].id").getFirst()).longValue();
    }

    private static String apply(long type, String from, String to) {
        return "{\"leaveTypeId\":%d,\"startDate\":\"%s\",\"endDate\":\"%s\",\"reason\":\"Family\"}".formatted(type, from, to);
    }

    private static String emp(String code, String device, String name, Long dept) {
        return "{\"employeeCode\":\"%s\",\"deviceUserId\":\"%s\",\"fullName\":\"%s\",\"departmentId\":%s}".formatted(code, device, name, dept);
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
