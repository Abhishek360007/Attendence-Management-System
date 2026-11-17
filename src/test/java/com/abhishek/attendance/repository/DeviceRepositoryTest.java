package com.abhishek.attendance.repository;

import com.abhishek.attendance.config.AttendanceProperties;
import com.abhishek.attendance.model.DevicePunch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real queries against Access databases built with UCanAccess, in both ZKTeco layouts.
 */
class DeviceRepositoryTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 17, 0, 0);

    @TempDir
    Path dir;

    @Test
    void readsDatabaseWithAutoIncrementColumn() throws Exception {
        DeviceRepository repo = repository(createDatabase(true));

        assertThat(repo.mode()).isEqualTo(DeviceRepository.Mode.AUTO_INCREMENT);

        List<DevicePunch> all = repo.fetchAfter(0, 100);
        assertThat(all).extracting(DevicePunch::autoIncrement).containsExactly(1L, 2L, 3L, 4L);
        DevicePunch neha = all.get(2);
        assertThat(neha.badgeNumber()).isEqualTo("103");          // padded value trimmed
        assertThat(neha.sensorId()).isNull();                      // empty SENSORID
        assertThat(neha.checkTime()).isEqualTo(DAY.withHour(10).withMinute(1).withSecond(5));
        assertThat(all.get(3).checkType()).isEqualTo("O");

        assertThat(repo.fetchAfter(2, 100)).extracting(DevicePunch::autoIncrement).containsExactly(3L, 4L);
        assertThat(repo.fetchAfter(0, 2)).hasSize(2);
    }

    @Test
    void readsStandardZkTimeDatabaseByCheckTime() throws Exception {
        DeviceRepository repo = repository(createDatabase(false));

        assertThat(repo.mode()).isEqualTo(DeviceRepository.Mode.CHECK_TIME);

        List<DevicePunch> all = repo.fetchAfterTime(LocalDateTime.of(1970, 1, 1, 0, 0), 100);
        assertThat(all).extracting(DevicePunch::badgeNumber).containsExactly("101", "102", "103", "101");
        assertThat(all).extracting(DevicePunch::autoIncrement).doesNotHaveDuplicates();
        // Ids are stable across reads, so re-reading a punch never stores it twice.
        assertThat(repo.fetchAfterTime(LocalDateTime.of(1970, 1, 1, 0, 0), 100))
                .extracting(DevicePunch::autoIncrement)
                .containsExactlyElementsOf(all.stream().map(DevicePunch::autoIncrement).toList());

        assertThat(repo.fetchAfterTime(DAY.withHour(9).withMinute(15).withSecond(40), 100))
                .extracting(DevicePunch::badgeNumber).containsExactly("103", "101");
        assertThat(repo.fetchTimeRange(DAY.withHour(9), DAY.withHour(10).withMinute(1).withSecond(5)))
                .extracting(DevicePunch::badgeNumber).containsExactly("101", "102", "103");
    }

    @Test
    void syntheticIdsAreUniquePerUserAndSecond() {
        LocalDateTime t = DAY.withHour(9);
        assertThat(DeviceRepository.syntheticId(1, t)).isNotEqualTo(DeviceRepository.syntheticId(2, t));
        assertThat(DeviceRepository.syntheticId(1, t)).isNotEqualTo(DeviceRepository.syntheticId(1, t.plusSeconds(1)));
        assertThat(DeviceRepository.syntheticId(1, t)).isEqualTo(DeviceRepository.syntheticId(1, t));
    }

    private static DeviceRepository repository(Path mdb) {
        return new DeviceRepository(new AttendanceProperties("", null,
                new AttendanceProperties.Device(mdb.toString().replace('\\', '/')),
                new AttendanceProperties.Sync(true, "-", 5000, 7)));
    }

    /** Same tables and column types as ZKTime's ATT2000.MDB, with or without an Auto_increment column. */
    private Path createDatabase(boolean withAutoIncrement) throws Exception {
        Path file = dir.resolve(withAutoIncrement ? "auto.mdb" : "standard.mdb");
        try (Connection c = DriverManager.getConnection("jdbc:ucanaccess://" + file + ";newDatabaseVersion=V2003");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE USERINFO (USERID COUNTER PRIMARY KEY, Badgenumber TEXT(24), Name TEXT(40))");
            s.execute("CREATE TABLE CHECKINOUT (USERID LONG, CHECKTIME DATETIME, CHECKTYPE TEXT(1), VERIFYCODE LONG, "
                    + "SENSORID TEXT(5)" + (withAutoIncrement ? ", Auto_increment COUNTER PRIMARY KEY" : "") + ")");
            s.execute("INSERT INTO USERINFO (Badgenumber, Name) VALUES ('101', 'Asha Verma')");
            s.execute("INSERT INTO USERINFO (Badgenumber, Name) VALUES ('102', 'Rahul Singh')");
            s.execute("INSERT INTO USERINFO (Badgenumber, Name) VALUES ('  103 ', 'Neha Gupta')");
            String insert = "INSERT INTO CHECKINOUT (USERID, CHECKTIME, CHECKTYPE, VERIFYCODE, SENSORID) VALUES ";
            s.execute(insert + "(1, #2026-11-17 09:02:11#, 'I', 1, '1')");
            s.execute(insert + "(2, #2026-11-17 09:15:40#, 'I', 1, '1')");
            s.execute(insert + "(3, #2026-11-17 10:01:05#, 'I', 1, NULL)");
            s.execute(insert + "(1, #2026-11-17 18:10:00#, 'O', 1, '2')");
            // A punch from a user no longer in USERINFO is skipped by the join.
            s.execute(insert + "(99, #2026-11-17 11:00:00#, 'I', 1, '1')");
        }
        return file;
    }
}
