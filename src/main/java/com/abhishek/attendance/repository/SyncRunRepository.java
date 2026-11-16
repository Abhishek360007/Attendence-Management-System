package com.abhishek.attendance.repository;

import com.abhishek.attendance.model.SyncRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class SyncRunRepository {

    private static final RowMapper<SyncRun> ROW_MAPPER = (rs, i) -> {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new SyncRun(
                rs.getLong("id"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                finished == null ? null : finished.toLocalDateTime(),
                rs.getLong("last_source_id"),
                rs.getInt("records_synced"),
                SyncRun.Status.valueOf(rs.getString("status")),
                rs.getString("message"));
    };

    private final JdbcTemplate jdbc;

    public SyncRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(SyncRun run) {
        String message = run.message();
        if (message != null && message.length() > 512) {
            message = message.substring(0, 512);
        }
        jdbc.update("""
                        INSERT INTO sync_run (started_at, finished_at, last_source_id, records_synced, status, message)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                Timestamp.valueOf(run.startedAt()),
                run.finishedAt() == null ? null : Timestamp.valueOf(run.finishedAt()),
                run.lastSourceId(),
                run.recordsSynced(),
                run.status().name(),
                message);
    }

    public Optional<SyncRun> findLatest() {
        return findRecent(1).stream().findFirst();
    }

    public List<SyncRun> findRecent(int limit) {
        return jdbc.query("SELECT * FROM sync_run ORDER BY id DESC LIMIT ?", ROW_MAPPER, limit);
    }
}
