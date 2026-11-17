CREATE TABLE IF NOT EXISTS attendance_record (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id   VARCHAR(64)  NOT NULL,
    employee_name VARCHAR(128),
    check_time    TIMESTAMP    NOT NULL,
    check_type    VARCHAR(8),
    device_id     INTEGER,
    source_id     BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_attendance_source UNIQUE (source_id)
);

CREATE INDEX IF NOT EXISTS idx_attendance_employee_time ON attendance_record (employee_id, check_time);
CREATE INDEX IF NOT EXISTS idx_attendance_check_time ON attendance_record (check_time);

CREATE TABLE IF NOT EXISTS sync_run (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at     TIMESTAMP    NOT NULL,
    finished_at    TIMESTAMP,
    last_source_id BIGINT       NOT NULL,
    records_synced INTEGER      NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL,
    message        VARCHAR(512)
);
