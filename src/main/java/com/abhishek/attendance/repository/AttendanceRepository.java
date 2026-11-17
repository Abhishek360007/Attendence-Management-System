package com.abhishek.attendance.repository;

import com.abhishek.attendance.model.AttendanceRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Repository
public class AttendanceRepository {

    private static final RowMapper<AttendanceRecord> ROW_MAPPER = (rs, i) -> new AttendanceRecord(
            rs.getLong("id"),
            rs.getString("employee_id"),
            rs.getString("employee_name"),
            rs.getTimestamp("check_time").toLocalDateTime(),
            rs.getString("check_type"),
            (Integer) rs.getObject("device_id", Integer.class),
            rs.getLong("source_id"),
            rs.getString("source"),
            rs.getBoolean("voided"));

    private static final String SELECT = """
            SELECT id, employee_id, employee_name, check_time, check_type, device_id, source_id, source, voided
            FROM attendance_record
            """;

    private final JdbcTemplate jdbc;

    public AttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts records, silently skipping any whose source id is already stored.
     *
     * @return number of rows actually inserted
     */
    public int insertAll(List<AttendanceRecord> records) {
        if (records.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO attendance_record
                    (employee_id, employee_name, check_time, check_type, device_id, source_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id) DO NOTHING
                """;
        int[][] results = jdbc.batchUpdate(sql, records, 500, (ps, r) -> {
            ps.setString(1, r.employeeId());
            ps.setString(2, r.employeeName());
            ps.setTimestamp(3, Timestamp.valueOf(r.checkTime()));
            ps.setString(4, r.checkType());
            if (r.deviceId() == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, r.deviceId());
            }
            ps.setLong(6, r.sourceId());
        });
        return Arrays.stream(results).flatMapToInt(Arrays::stream).map(n -> n < 0 ? 1 : n).sum();
    }

    /** Records stored after record {@code id}, in insertion order: the incremental feed for downstream systems. */
    public List<AttendanceRecord> findAfterId(long id, int limit) {
        return jdbc.query(SELECT + " WHERE id > ? ORDER BY id LIMIT ?", ROW_MAPPER, id, limit);
    }

    public LocalDateTime maxCheckTime() {
        Timestamp max = jdbc.queryForObject("SELECT MAX(check_time) FROM attendance_record WHERE source = 'DEVICE'", Timestamp.class);
        return max == null ? null : max.toLocalDateTime();
    }

    public List<AttendanceRecord> search(String employeeId, LocalDateTime from, LocalDateTime to, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE check_time >= ? AND check_time < ?");
        List<Object> args = new ArrayList<>(List.of(Timestamp.valueOf(from), Timestamp.valueOf(to)));
        if (employeeId != null && !employeeId.isBlank()) {
            sql.append(" AND employee_id = ?");
            args.add(employeeId);
        }
        sql.append(" ORDER BY check_time, id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long maxSourceId() {
        Long max = jdbc.queryForObject("SELECT MAX(source_id) FROM attendance_record WHERE source = 'DEVICE'", Long.class);
        return max == null ? 0L : max;
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM attendance_record", Long.class);
        return count == null ? 0L : count;
    }
}
