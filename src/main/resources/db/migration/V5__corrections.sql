-- Requests to add, change or remove a punch. Device punches are never edited in place: an approved change voids
-- the original and adds a MANUAL punch, so the original and corrected times are both kept.
CREATE TABLE punch_correction (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id          BIGINT       NOT NULL REFERENCES employee (id),
    action               VARCHAR(8)   NOT NULL CHECK (action IN ('ADD', 'MODIFY', 'REMOVE')),
    -- The punch being changed or removed (MODIFY, REMOVE).
    attendance_record_id BIGINT REFERENCES attendance_record (id),
    original_time        TIMESTAMP,
    -- The time to add, or the corrected time (ADD, MODIFY).
    requested_time       TIMESTAMP,
    reason               VARCHAR(500) NOT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by         BIGINT REFERENCES app_user (id),
    decided_by           BIGINT REFERENCES app_user (id),
    decided_at           TIMESTAMP,
    decision_note        VARCHAR(500),
    -- The MANUAL punch created when an ADD or MODIFY was approved.
    applied_record_id    BIGINT REFERENCES attendance_record (id),
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((action = 'ADD' AND requested_time IS NOT NULL AND attendance_record_id IS NULL)
        OR (action = 'MODIFY' AND requested_time IS NOT NULL AND attendance_record_id IS NOT NULL)
        OR (action = 'REMOVE' AND requested_time IS NULL AND attendance_record_id IS NOT NULL))
);
CREATE INDEX idx_punch_correction_employee ON punch_correction (employee_id, created_at);
CREATE INDEX idx_punch_correction_status ON punch_correction (status);

ALTER TABLE attendance_record ADD COLUMN correction_id BIGINT REFERENCES punch_correction (id);
CREATE INDEX idx_attendance_record_check_time ON attendance_record (check_time);
