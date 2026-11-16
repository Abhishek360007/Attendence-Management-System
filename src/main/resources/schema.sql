CREATE TABLE IF NOT EXISTS attendance_record (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    employee_id   VARCHAR(64)  NOT NULL,
    employee_name VARCHAR(128),
    check_time    TIMESTAMP    NOT NULL,
    check_type    VARCHAR(8),
    device_id     INT,
    source_id     BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_source (source_id),
    INDEX idx_attendance_employee_time (employee_id, check_time)
);

CREATE TABLE IF NOT EXISTS sync_run (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    started_at     TIMESTAMP   NOT NULL,
    finished_at    TIMESTAMP   NULL,
    last_source_id BIGINT      NOT NULL,
    records_synced INT         NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL,
    message        VARCHAR(512),
    PRIMARY KEY (id)
);
