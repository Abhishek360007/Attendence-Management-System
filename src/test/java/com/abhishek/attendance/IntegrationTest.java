package com.abhishek.attendance;

import com.abhishek.attendance.auth.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

/**
 * Base for tests against a real PostgreSQL (one container shared by all test classes).
 * Every table except Flyway's history is emptied before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    static {
        postgres.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @Autowired
    protected com.abhishek.attendance.settings.SettingsService settingsService;

    @BeforeEach
    void emptyDatabase() {
        loginRateLimiter.reset();
        settingsService.evictCache();
        List<String> tables = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
        // Rows the migrations seed.
        jdbc.update("INSERT INTO company_setting (key, value) VALUES ('company_name', 'My Company'), ('timezone', 'Asia/Kolkata')");
        jdbc.update("""
                INSERT INTO work_schedule (name, start_time, end_time, weekly_offs, is_default)
                VALUES ('General shift', '09:00', '18:00', 'SUNDAY', TRUE)""");
        jdbc.update("""
                INSERT INTO leave_type (name, paid, annual_quota) VALUES
                ('Casual leave', TRUE, 12), ('Sick leave', TRUE, 12), ('Earned leave', TRUE, 15), ('Unpaid leave', FALSE, NULL)""");
    }

    protected Browser browser() {
        return new Browser(mvc);
    }
}
