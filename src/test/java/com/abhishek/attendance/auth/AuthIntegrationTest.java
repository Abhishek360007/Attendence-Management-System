package com.abhishek.attendance.auth;

import com.abhishek.attendance.Browser;
import com.abhishek.attendance.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    UserService users;

    @Test
    void firstRunSetupNeedsTheApiKeyAndOnlyWorksOnce() throws Exception {
        Browser b = browser();
        b.get("/api/auth/setup").andExpect(jsonPath("$.needed").value(true));

        b.post("/api/auth/setup", setup("wrong")).andExpect(status().isBadRequest());
        b.post("/api/auth/setup", setup("test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist())
                .andExpect(jsonPath("$.lastLoginAt").exists());

        // Setup signed the admin in.
        b.get("/api/auth/me").andExpect(jsonPath("$.email").value("owner@example.com"));
        browser().get("/api/auth/setup").andExpect(jsonPath("$.needed").value(false));
        browser().post("/api/auth/setup", setup("test-key")).andExpect(status().isConflict());
    }

    @Test
    void loginLogoutAndRoleChecks() throws Exception {
        users.create("admin@example.com", "Admin", "admin-pass-1", Role.ADMIN);
        users.create("emp@example.com", "Employee", "emp-pass-1", Role.EMPLOYEE);

        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        browser().post("/api/auth/login", "{\"email\":\"emp@example.com\",\"password\":\"nope-nope\"}")
                .andExpect(status().isUnauthorized());

        Browser emp = browser().login("EMP@example.com", "emp-pass-1");
        emp.get("/api/auth/me").andExpect(jsonPath("$.role").value("EMPLOYEE"));
        emp.get("/api/users").andExpect(status().isForbidden());
        emp.get("/api/attendance").andExpect(status().isForbidden());

        Browser admin = browser().login("admin@example.com", "admin-pass-1");
        admin.get("/api/users").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        admin.get("/api/attendance").andExpect(status().isOk());

        admin.post("/api/auth/logout", null).andExpect(status().isNoContent());
        admin.get("/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void stateChangingRequestsNeedTheCsrfToken() throws Exception {
        users.create("admin@example.com", "Admin", "admin-pass-1", Role.ADMIN);
        Browser admin = browser().login("admin@example.com", "admin-pass-1");

        String newUser = "{\"email\":\"x@example.com\",\"name\":\"X\",\"password\":\"x-pass-123\",\"role\":\"HR\"}";
        admin.postWithoutCsrf("/api/users", newUser).andExpect(status().isForbidden());
        admin.post("/api/users", newUser).andExpect(status().isOk());
    }

    @Test
    void deactivatingAUserEndsTheirSessionAndIsAudited() throws Exception {
        users.create("admin@example.com", "Admin", "admin-pass-1", Role.ADMIN);
        long hrId = users.create("hr@example.com", "HR Person", "hr-pass-12", Role.HR).id();
        Browser admin = browser().login("admin@example.com", "admin-pass-1");
        Browser hr = browser().login("hr@example.com", "hr-pass-12");
        hr.get("/api/auth/me").andExpect(status().isOk());

        admin.put("/api/users/{id}", "{\"name\":\"HR Person\",\"role\":\"HR\",\"active\":false}", hrId)
                .andExpect(jsonPath("$.active").value(false));

        hr.get("/api/auth/me").andExpect(status().isUnauthorized());
        browser().post("/api/auth/login", "{\"email\":\"hr@example.com\",\"password\":\"hr-pass-12\"}")
                .andExpect(status().isUnauthorized());
        admin.get("/api/audit?entityType=user&entityId=" + hrId)
                .andExpect(jsonPath("$[0].action").value("USER_UPDATED"))
                .andExpect(jsonPath("$[0].details").value("deactivated"))
                .andExpect(jsonPath("$[0].actorName").value("Admin <admin@example.com>"));
    }

    @Test
    void adminCannotLockThemselvesOut() throws Exception {
        long id = users.create("admin@example.com", "Admin", "admin-pass-1", Role.ADMIN).id();
        browser().login("admin@example.com", "admin-pass-1")
                .put("/api/users/{id}", "{\"name\":\"Admin\",\"role\":\"HR\",\"active\":true}", id)
                .andExpect(status().isConflict());
    }

    @Test
    void changingOwnPasswordRequiresTheCurrentOne() throws Exception {
        users.create("emp@example.com", "Employee", "emp-pass-1", Role.EMPLOYEE);
        Browser emp = browser().login("emp@example.com", "emp-pass-1");

        emp.post("/api/auth/password", "{\"currentPassword\":\"wrong-one\",\"newPassword\":\"new-pass-99\"}")
                .andExpect(status().isBadRequest());
        emp.post("/api/auth/password", "{\"currentPassword\":\"emp-pass-1\",\"newPassword\":\"short\"}")
                .andExpect(status().isBadRequest());
        emp.post("/api/auth/password", "{\"currentPassword\":\"emp-pass-1\",\"newPassword\":\"new-pass-99\"}")
                .andExpect(status().isNoContent());
        browser().login("emp@example.com", "new-pass-99");
    }

    @Test
    void repeatedFailuresAreThrottled() throws Exception {
        users.create("emp@example.com", "Employee", "emp-pass-1", Role.EMPLOYEE);
        String bad = "{\"email\":\"emp@example.com\",\"password\":\"guess-guess\"}";
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            browser().post("/api/auth/login", bad).andExpect(status().isUnauthorized());
        }
        int code = browser().post("/api/auth/login", "{\"email\":\"emp@example.com\",\"password\":\"emp-pass-1\"}")
                .andReturn().getResponse().getStatus();
        assertThat(code).isEqualTo(429);
    }

    @Test
    void apiKeyStillWorksForMachineClients() throws Exception {
        mvc.perform(get("/api/sync/status").header("X-API-Key", "test-key")).andExpect(status().isOk());
        mvc.perform(get("/api/sync/status").header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users").header("X-API-Key", "test-key")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private static String setup(String key) {
        return """
                {"setupKey":"%s","email":"owner@example.com","name":"Owner","password":"owner-pass-1"}""".formatted(key);
    }
}
