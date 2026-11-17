CREATE TABLE leave_type (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(60)  NOT NULL,
    paid         BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Days per calendar year. NULL means no limit (typical for unpaid leave).
    annual_quota NUMERIC(5, 1) CHECK (annual_quota >= 0),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_leave_type_name ON leave_type (LOWER(name));
INSERT INTO leave_type (name, paid, annual_quota) VALUES
    ('Casual leave', TRUE, 12),
    ('Sick leave', TRUE, 12),
    ('Earned leave', TRUE, 15),
    ('Unpaid leave', FALSE, NULL);

-- Per-employee override of a type's yearly quota (opening balance, carry-forward, pro-rating).
CREATE TABLE leave_allocation (
    employee_id   BIGINT       NOT NULL REFERENCES employee (id),
    leave_type_id BIGINT       NOT NULL REFERENCES leave_type (id),
    year          INTEGER      NOT NULL,
    days          NUMERIC(5, 1) NOT NULL CHECK (days >= 0),
    PRIMARY KEY (employee_id, leave_type_id, year)
);

CREATE TABLE leave_request (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id   BIGINT       NOT NULL REFERENCES employee (id),
    leave_type_id BIGINT       NOT NULL REFERENCES leave_type (id),
    start_date    DATE         NOT NULL,
    end_date      DATE         NOT NULL,
    -- Working days in the range (holidays and weekly offs excluded), fixed when the request is made.
    days          NUMERIC(5, 1) NOT NULL,
    reason        VARCHAR(500),
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by  BIGINT REFERENCES app_user (id),
    decided_by    BIGINT REFERENCES app_user (id),
    decided_at    TIMESTAMP,
    decision_note VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (end_date >= start_date)
);
CREATE INDEX idx_leave_request_employee ON leave_request (employee_id, start_date, end_date);
CREATE INDEX idx_leave_request_status ON leave_request (status);
