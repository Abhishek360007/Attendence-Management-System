# Attendance Management System

A Spring Boot service that syncs punch records from **ZKTeco biometric attendance devices** into **MySQL** and serves them through a REST API.

ZKTeco's desktop software (ZKTime / ZKAccess) stores every punch it downloads from the devices in an MS Access database, usually `ATT2000.MDB`. This service reads that file on a schedule, copies new punches into MySQL, and gives other systems (HR, payroll, dashboards) a clean API to work with.

## Features

- **Incremental sync**: only new punches are read on each run. Re-running is safe because duplicates are ignored at the database level.
- **No data loss on failure**: progress is worked out from the stored records, so a failed run never skips anything.
- **Sync history**: failed runs, and runs that copied records, are logged with counts and error messages.
- **Manual trigger**: start a sync on demand over the API. Overlapping runs are rejected.
- **Daily summary**: first-in / last-out and worked minutes per employee per day.
- **Configurable through environment variables**: no credentials in source.
- Health endpoint through Spring Boot Actuator.

## Tech stack

Java 17 · Spring Boot 3.5 · Spring JDBC · MySQL · UCanAccess (MS Access JDBC driver) · JUnit 5 + H2 for tests

## Getting started

### Prerequisites

- JDK 17 or newer
- MySQL 8 (or MariaDB)
- Read access to the ZKTeco `ATT2000.MDB` file (on the same machine or a mapped network drive)

### 1. Create the database

```sql
CREATE DATABASE attendance;
```

The tables (`attendance_record`, `sync_run`) are created automatically on first start from [`schema.sql`](src/main/resources/schema.sql).

### 2. Configure

Set these environment variables (defaults are in brackets):

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | MySQL JDBC URL | `jdbc:mysql://localhost:3306/attendance?...` |
| `DB_USERNAME` | MySQL user | `root` |
| `DB_PASSWORD` | MySQL password | *(empty)* |
| `ZKTECO_MDB_PATH` | Path to the ZKTeco Access database | `C:/ZKTeco/ATT2000.MDB` |
| `SYNC_ENABLED` | Turn the scheduled sync on or off | `true` |
| `SYNC_CRON` | Sync schedule (Spring cron, 6 fields) | `0 */1 * * * *` (every minute) |
| `SYNC_BATCH_SIZE` | Maximum punches read per run | `5000` |
| `SERVER_PORT` | HTTP port | `9010` |

### 3. Build and run

```bash
./mvnw clean package
java -jar target/attendance-management-system.jar
```

On Windows (PowerShell):

```powershell
$env:DB_PASSWORD = "secret"
$env:ZKTECO_MDB_PATH = "D:/ZKTeco/ATT2000.MDB"
.\mvnw.cmd spring-boot:run
```

## REST API

Dates use `yyyy-MM-dd` and are inclusive. If you leave out `from` and `to`, today is used. One request can cover at most 366 days.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/attendance?employeeId=&from=&to=&page=0&size=100` | Search punches |
| `GET` | `/api/attendance/employees/{employeeId}?from=&to=` | Punches for one employee |
| `GET` | `/api/attendance/summary?employeeId=&from=&to=` | Daily first-in / last-out summary |
| `GET` | `/api/attendance/feed?afterId=0&limit=500` | Incremental feed for downstream systems |
| `POST` | `/api/sync` | Run a sync now |
| `GET` | `/api/sync/status` | Total records, last synced device id, last run |
| `GET` | `/api/sync/history?limit=20` | Recent sync runs |
| `GET` | `/actuator/health` | Health check |

Example:

```bash
curl "http://localhost:9010/api/attendance/summary?from=2026-11-01&to=2026-11-07"
```

```json
[
  {
    "employeeId": "101",
    "employeeName": "Employee 101",
    "date": "2026-11-02",
    "firstIn": "2026-11-02T09:00:00",
    "lastOut": "2026-11-02T18:00:00",
    "punches": 2,
    "workedMinutes": 540
  }
]
```

## Project structure

```
src/main/java/com/abhishek/attendance
├── config/       # Typed configuration and scheduling
├── model/        # Records: AttendanceRecord, DevicePunch, SyncRun, DailySummary
├── repository/   # JDBC access to MySQL and the ZKTeco Access database
├── service/      # Sync logic and attendance queries
├── scheduler/    # Cron-triggered sync
└── web/          # REST controllers and error handling
```

## Testing

```bash
./mvnw test
```

The integration tests run against an in-memory H2 database in MySQL mode, with the ZKTeco source mocked. You do not need MySQL or an `.mdb` file to run them.

## Acknowledgements

This project was inspired by [RumiAust/Zkteco-Attendance-Management-System](https://github.com/RumiAust/Zkteco-Attendance-Management-System).
