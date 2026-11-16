package com.abhishek.attendance;

import com.abhishek.attendance.model.DevicePunch;
import com.abhishek.attendance.model.SyncRun;
import com.abhishek.attendance.repository.DeviceRepository;
import com.abhishek.attendance.service.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SyncIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 2, 0, 0);

    @MockitoBean
    DeviceRepository deviceRepository;

    @Autowired
    SyncService syncService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM attendance_record");
        jdbc.update("DELETE FROM sync_run");
    }

    @Test
    void syncsNewPunchesIncrementallyWithoutDuplicates() throws Exception {
        when(deviceRepository.fetchAfter(eq(0L), anyInt())).thenReturn(List.of(
                punch(1, "101", DAY.withHour(9)),
                punch(2, "102", DAY.withHour(9).withMinute(5)),
                punch(3, "101", DAY.withHour(18))));
        when(deviceRepository.fetchAfter(eq(3L), anyInt())).thenReturn(List.of(
                punch(3, "101", DAY.withHour(18)),   // already stored, must be ignored
                punch(4, "102", DAY.withHour(17).withMinute(30))));

        SyncRun first = syncService.sync();
        assertThat(first.status()).isEqualTo(SyncRun.Status.SUCCESS);
        assertThat(first.recordsSynced()).isEqualTo(3);
        assertThat(first.lastSourceId()).isEqualTo(3);

        SyncRun second = syncService.sync();
        assertThat(second.recordsSynced()).isEqualTo(1);
        assertThat(second.lastSourceId()).isEqualTo(4);

        mvc.perform(api(get("/api/attendance").param("from", "2026-11-02")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].checkTime").value("2026-11-02T09:00:00"));

        mvc.perform(api(get("/api/attendance/summary").param("from", "2026-11-02").param("employeeId", "101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].punches").value(2))
                .andExpect(jsonPath("$[0].workedMinutes").value(540));

        mvc.perform(api(get("/api/attendance/feed").param("afterId", "2")))
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(api(get("/api/sync/status")))
                .andExpect(jsonPath("$.totalRecords").value(4))
                .andExpect(jsonPath("$.lastSourceId").value(4));
    }

    @Test
    void failedDeviceReadIsRecordedAndDoesNotAdvance() throws Exception {
        when(deviceRepository.fetchAfter(anyLong(), anyInt())).thenThrow(new SQLException("file locked"));

        SyncRun run = syncService.sync();

        assertThat(run.status()).isEqualTo(SyncRun.Status.FAILED);
        assertThat(run.message()).contains("file locked");
        assertThat(run.lastSourceId()).isZero();
        mvc.perform(api(get("/api/sync/history")))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void rejectsInvalidDateRange() throws Exception {
        mvc.perform(api(get("/api/attendance").param("from", "2026-11-05").param("to", "2026-11-01")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresApiKeyButLeavesHealthOpen() throws Exception {
        mvc.perform(get("/api/attendance")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/attendance").header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request) {
        return request.header("X-API-Key", "test-key");
    }

    private static DevicePunch punch(long id, String badge, LocalDateTime time) {
        return new DevicePunch(id, badge, "Employee " + badge, time, "I", 1);
    }
}
