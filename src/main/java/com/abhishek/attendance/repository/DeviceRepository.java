package com.abhishek.attendance.repository;

import com.abhishek.attendance.config.AttendanceProperties;
import com.abhishek.attendance.model.DevicePunch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads punches from the MS Access database maintained by ZKTeco software (ZKTime / ZKAccess, usually ATT2000.MDB).
 * <p>
 * Some databases number every punch in a {@code CHECKINOUT.Auto_increment} column; the standard ZKTime layout
 * does not, and identifies a punch by {@code USERID} + {@code CHECKTIME}. The layout is detected on first use.
 */
@Repository
public class DeviceRepository {

    public enum Mode {
        /** New punches are those with a higher {@code Auto_increment} than the last one stored. */
        AUTO_INCREMENT,
        /** New punches are found by {@code CHECKTIME}; each gets a stable id derived from user and time. */
        CHECK_TIME
    }

    private static final Logger log = LoggerFactory.getLogger(DeviceRepository.class);

    private static final String SELECT = """
            SELECT %s c.USERID AS USER_ID, %s u.Badgenumber AS BADGE, u.Name AS NAME,
                   c.CHECKTIME AS CHECK_TIME, c.CHECKTYPE AS CHECK_TYPE, c.SENSORID AS SENSOR_ID
            FROM CHECKINOUT c
            INNER JOIN USERINFO u ON c.USERID = u.USERID
            """;

    /** Room for epoch seconds up to the year 2286 below the user id. */
    private static final long USER_ID_FACTOR = 10_000_000_000L;

    private final AttendanceProperties properties;
    private volatile Mode mode;

    public DeviceRepository(AttendanceProperties properties) {
        this.properties = properties;
    }

    public Mode mode() throws SQLException {
        Mode detected = mode;
        if (detected == null) {
            detected = Mode.CHECK_TIME;
            try (Connection connection = open();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT TOP 1 * FROM CHECKINOUT")) {
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    if ("Auto_increment".equalsIgnoreCase(meta.getColumnName(i))) {
                        detected = Mode.AUTO_INCREMENT;
                    }
                }
            }
            log.info("ZKTeco database layout: {} (new punches are found by {})", detected,
                    detected == Mode.AUTO_INCREMENT ? "CHECKINOUT.Auto_increment" : "CHECKINOUT.CHECKTIME");
            mode = detected;
        }
        return detected;
    }

    /** {@link Mode#AUTO_INCREMENT}: punches numbered above {@code lastId}, oldest first. */
    public List<DevicePunch> fetchAfter(long lastId, int limit) throws SQLException {
        String sql = SELECT.formatted("TOP " + limit, "c.Auto_increment AS AUTO_ID,")
                + " WHERE c.Auto_increment > ? ORDER BY c.Auto_increment";
        return query(sql, true, lastId);
    }

    /** {@link Mode#CHECK_TIME}: punches strictly after {@code after}, oldest first. */
    public List<DevicePunch> fetchAfterTime(LocalDateTime after, int limit) throws SQLException {
        String sql = SELECT.formatted("TOP " + limit, "")
                + " WHERE c.CHECKTIME > ? ORDER BY c.CHECKTIME, c.USERID";
        return query(sql, false, Timestamp.valueOf(after));
    }

    /** {@link Mode#CHECK_TIME}: every punch from {@code from} to {@code to}, both inclusive. */
    public List<DevicePunch> fetchTimeRange(LocalDateTime from, LocalDateTime to) throws SQLException {
        String sql = SELECT.formatted("", "")
                + " WHERE c.CHECKTIME >= ? AND c.CHECKTIME <= ? ORDER BY c.CHECKTIME, c.USERID";
        return query(sql, false, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    /** Stable id for a punch without an Auto_increment value: the same user and second always give the same id. */
    static long syntheticId(long userId, LocalDateTime checkTime) {
        return userId * USER_ID_FACTOR + checkTime.toEpochSecond(ZoneOffset.UTC);
    }

    private List<DevicePunch> query(String sql, boolean hasAutoId, Object... params) throws SQLException {
        List<DevicePunch> punches = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Timestamp checkTime = rs.getTimestamp("CHECK_TIME");
                    if (checkTime == null) {
                        continue;
                    }
                    LocalDateTime time = checkTime.toLocalDateTime();
                    long id = hasAutoId ? rs.getLong("AUTO_ID") : syntheticId(rs.getLong("USER_ID"), time);
                    punches.add(new DevicePunch(
                            id,
                            trim(rs.getString("BADGE")),
                            trim(rs.getString("NAME")),
                            time,
                            trim(rs.getString("CHECK_TYPE")),
                            parseSensor(rs.getString("SENSOR_ID"))));
                }
            }
        }
        return punches;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(properties.device().jdbcUrl());
    }

    /** SENSORID is a text column in most ZKTeco databases. */
    static Integer parseSensor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
