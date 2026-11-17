-- Company-wide settings, edited by admins.
CREATE TABLE company_setting (
    key   VARCHAR(64) PRIMARY KEY,
    value TEXT        NOT NULL
);
INSERT INTO company_setting (key, value) VALUES
    ('company_name', 'My Company'),
    ('timezone', 'Asia/Kolkata');

-- A working pattern: hours, grace periods and weekly days off.
CREATE TABLE work_schedule (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                 VARCHAR(80) NOT NULL UNIQUE,
    start_time           TIME        NOT NULL,
    end_time             TIME        NOT NULL,
    -- Arriving within this many minutes after start_time is not late.
    late_grace_minutes   INTEGER     NOT NULL DEFAULT 10 CHECK (late_grace_minutes BETWEEN 0 AND 240),
    -- Leaving within this many minutes before end_time is not an early departure.
    early_grace_minutes  INTEGER     NOT NULL DEFAULT 10 CHECK (early_grace_minutes BETWEEN 0 AND 240),
    -- Extra time counts as overtime only once it reaches this many minutes.
    overtime_min_minutes INTEGER     NOT NULL DEFAULT 30 CHECK (overtime_min_minutes BETWEEN 0 AND 600),
    -- Working fewer minutes than this (with both an in and an out punch) is a half day. 0 turns it off.
    half_day_minutes     INTEGER     NOT NULL DEFAULT 240 CHECK (half_day_minutes BETWEEN 0 AND 1440),
    -- Comma-separated java.time.DayOfWeek names, e.g. 'SATURDAY,SUNDAY'.
    weekly_offs          VARCHAR(80) NOT NULL DEFAULT 'SUNDAY',
    is_default           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_work_schedule_default ON work_schedule (is_default) WHERE is_default;
INSERT INTO work_schedule (name, start_time, end_time, weekly_offs, is_default)
VALUES ('General shift', '09:00', '18:00', 'SUNDAY', TRUE);

CREATE TABLE department (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                VARCHAR(80) NOT NULL,
    work_schedule_id    BIGINT REFERENCES work_schedule (id),
    manager_employee_id BIGINT,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_department_name ON department (LOWER(name));

CREATE TABLE employee (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_code    VARCHAR(32)  NOT NULL,
    -- The user number on the ZKTeco devices (USERINFO.Badgenumber); links punches to this employee.
    device_user_id   VARCHAR(64),
    full_name        VARCHAR(128) NOT NULL,
    email            VARCHAR(160),
    phone            VARCHAR(32),
    department_id    BIGINT REFERENCES department (id),
    designation      VARCHAR(80),
    manager_id       BIGINT REFERENCES employee (id),
    -- Overrides the department's schedule when set.
    work_schedule_id BIGINT REFERENCES work_schedule (id),
    monthly_salary   NUMERIC(12, 2) CHECK (monthly_salary >= 0),
    joined_on        DATE,
    left_on          DATE,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    user_id          BIGINT REFERENCES app_user (id),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (manager_id IS NULL OR manager_id <> id)
);
CREATE UNIQUE INDEX uk_employee_code ON employee (LOWER(employee_code));
CREATE UNIQUE INDEX uk_employee_device_user ON employee (device_user_id) WHERE device_user_id IS NOT NULL;
CREATE UNIQUE INDEX uk_employee_user ON employee (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_employee_department ON employee (department_id);
CREATE INDEX idx_employee_manager ON employee (manager_id);

ALTER TABLE department ADD CONSTRAINT fk_department_manager FOREIGN KEY (manager_employee_id) REFERENCES employee (id);

CREATE TABLE holiday (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    holiday_date DATE         NOT NULL UNIQUE,
    name         VARCHAR(120) NOT NULL
);

-- Punch corrections: device punches are never edited. A corrected or removed punch is voided, and an added or
-- corrected time is stored as a MANUAL punch (see V5).
ALTER TABLE attendance_record ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'DEVICE';
ALTER TABLE attendance_record ADD COLUMN voided BOOLEAN NOT NULL DEFAULT FALSE;
