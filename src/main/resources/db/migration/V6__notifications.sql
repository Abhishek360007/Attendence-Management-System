-- In-app notifications (also emailed when a mail server is configured).
CREATE TABLE notification (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind       VARCHAR(32)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT,
    -- Dashboard route to open, e.g. '/leave?tab=approvals'.
    link       VARCHAR(200),
    read_at    TIMESTAMP,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notification_user ON notification (user_id, created_at DESC);
CREATE INDEX idx_notification_unread ON notification (user_id) WHERE read_at IS NULL;

-- Scheduled jobs claim a period here first, so the cloud API and the office-PC agent never both send a report.
CREATE TABLE job_run (
    job        VARCHAR(48) NOT NULL,
    period     VARCHAR(32) NOT NULL,
    claimed_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (job, period)
);
