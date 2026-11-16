package com.abhishek.attendance.repository;

import com.abhishek.attendance.config.AttendanceProperties;
import com.abhishek.attendance.model.DevicePunch;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads punches from the MS Access database maintained by ZKTeco software (ZKTime / ZKAccess, usually ATT2000.MDB).
 */
@Repository
public class DeviceRepository {

    private static final String QUERY = """
            SELECT TOP %d c.Auto_increment AS AUTO_ID, u.Badgenumber AS BADGE, u.Name AS NAME,
                   c.CHECKTIME AS CHECK_TIME, c.CHECKTYPE AS CHECK_TYPE, c.SENSORID AS SENSOR_ID
            FROM CHECKINOUT c
            INNER JOIN USERINFO u ON c.USERID = u.USERID
            WHERE c.Auto_increment > ?
            ORDER BY c.Auto_increment
            """;

    private final AttendanceProperties properties;

    public DeviceRepository(AttendanceProperties properties) {
        this.properties = properties;
    }

    public List<DevicePunch> fetchAfter(long lastId, int limit) throws SQLException {
        List<DevicePunch> punches = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(properties.device().jdbcUrl());
             PreparedStatement statement = connection.prepareStatement(QUERY.formatted(limit))) {
            statement.setLong(1, lastId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Timestamp checkTime = rs.getTimestamp("CHECK_TIME");
                    if (checkTime == null) {
                        continue;
                    }
                    punches.add(new DevicePunch(
                            rs.getLong("AUTO_ID"),
                            trim(rs.getString("BADGE")),
                            trim(rs.getString("NAME")),
                            checkTime.toLocalDateTime(),
                            trim(rs.getString("CHECK_TYPE")),
                            parseSensor(rs.getString("SENSOR_ID"))));
                }
            }
        }
        return punches;
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
