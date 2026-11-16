# Attendance Management System

A Spring Boot service that syncs punch records from **ZKTeco biometric attendance devices** into **PostgreSQL** and serves them through a REST API. It is designed to run on **Render** with a **Neon** serverless Postgres database.

ZKTeco's desktop software (ZKTime / ZKAccess) stores every punch it downloads from the devices in an MS Access database, usually `ATT2000.MDB`. This service reads that file on a schedule, copies new punches into PostgreSQL, and gives other systems (HR, payroll, dashboards) a clean API to work with.

## Architecture

```
 ZKTeco devices ──► ZKTime / ZKAccess (office PC) ──► ATT2000.MDB
                                                          │
                                   sync agent (this app, SYNC_ENABLED=true, office PC)
                                                          │  JDBC over SSL
                                                          ▼
                                                 Neon (PostgreSQL)
                                                          ▲
                                                          │
                       REST API (this app, SYNC_ENABLED=false, Render) ◄── HR / payroll / dashboards
```

The same jar runs in both places. The `.mdb` file only exists on the office PC, so that copy does the syncing and writes straight to Neon. The Render copy serves the API. Duplicate punches are ignored at the database level, so it is safe to restart either one at any time.

## Features

- **Incremental sync**: only new punches are read on each run. Re-running is safe because duplicates are ignored at the database level.
- **No data loss on failure**: progress is worked out from the stored records, so a failed run never skips anything.
- **Sync history**: failed runs, and runs that copied records, are logged with counts and error messages.
- **Manual trigger**: start a sync on demand over the API. Overlapping runs are rejected.
- **Daily summary**: first-in / last-out and worked minutes per employee per day.
- **API key protection** for every `/api/**` endpoint. The health check stays open for the hosting platform.
- **Configurable through environment variables**: no credentials in source.

## Tech stack

Java 25 (LTS) · Spring Boot 4.1 (Spring Framework 7, Jackson 3, Tomcat 11) · Spring JDBC · PostgreSQL 18 (Neon) · UCanAccess 5.3 (MS Access JDBC driver) · JUnit + Testcontainers · Docker · Render · Maven 3.9 (wrapper included)

## Deploying

### 1. Create the Neon database

1. Sign in at [neon.tech](https://neon.tech) and create a project. Pick the region closest to your Render region (for example AWS Singapore for Render Singapore).
2. On the project dashboard click **Connect**, turn on **Connection pooling**, and note the host, database, user and password.
3. Build the JDBC URL from them:

   ```
   jdbc:postgresql://<host>/<database>?sslmode=require
   ```

   Example: `jdbc:postgresql://ep-cool-river-a1b2c3-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`

The tables are created automatically on first start from [`schema.sql`](src/main/resources/schema.sql).

### 2. Deploy the API to Render

1. Sign in at [render.com](https://render.com) with GitHub.
2. Click **New → Blueprint** and pick this repository. Render reads [`render.yaml`](render.yaml) and builds the [`Dockerfile`](Dockerfile).
3. When asked, fill in `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` from Neon. `API_KEY` is generated for you; copy it from the service's **Environment** tab.
4. Once the deploy finishes, check it:

   ```bash
   curl https://<your-service>.onrender.com/actuator/health
   curl -H "X-API-Key: <API_KEY>" https://<your-service>.onrender.com/api/sync/status
   ```

On the free plan the service sleeps after about 15 minutes without traffic. The first request after that takes around a minute while it wakes up.

### 3. Run the sync agent on the office PC

On the Windows PC that runs ZKTime / ZKAccess, with JDK 25 installed:

```powershell
.\mvnw.cmd -DskipTests package

$env:DB_URL          = "jdbc:postgresql://<neon-host>/<database>?sslmode=require"
$env:DB_USERNAME     = "<neon-user>"
$env:DB_PASSWORD     = "<neon-password>"
$env:ZKTECO_MDB_PATH = "C:/ZKTeco/ATT2000.MDB"
$env:SYNC_ENABLED    = "true"
java -jar target\attendance-management-system.jar
```

To keep it running in the background and starting with Windows, register that command as a service with a wrapper such as [WinSW](https://github.com/winsw/winsw) or [NSSM](https://nssm.cc).

## Configuration

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/attendance` |
| `DB_USERNAME` | Database user | `postgres` |
| `DB_PASSWORD` | Database password | *(empty)* |
| `DB_POOL_SIZE` | Maximum database connections | `5` |
| `API_KEY` | When set, `/api/**` requires the `X-API-Key` header | *(empty: no auth)* |
| `ZKTECO_MDB_PATH` | Path to the ZKTeco Access database | `C:/ZKTeco/ATT2000.MDB` |
| `SYNC_ENABLED` | Run the scheduled sync (`false` on cloud instances) | `true` (the Docker image sets `false`) |
| `SYNC_CRON` | Sync schedule (Spring cron, 6 fields) | `0 */1 * * * *` (every minute) |
| `SYNC_BATCH_SIZE` | Maximum punches read per run | `5000` |
| `PORT` / `SERVER_PORT` | HTTP port (`PORT` is set by Render) | `9010` |

## Running locally

With a local PostgreSQL:

```powershell
$env:DB_PASSWORD = "secret"
$env:ZKTECO_MDB_PATH = "D:/ZKTeco/ATT2000.MDB"
.\mvnw.cmd spring-boot:run
```

Or with Docker (API only):

```bash
docker build -t attendance-management-system .
docker run -p 9010:9010 -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... attendance-management-system
```

## REST API

When `API_KEY` is set, every request to `/api/**` needs the header `X-API-Key: <API_KEY>`.

Dates use `yyyy-MM-dd` and are inclusive. If you leave out `from` and `to`, today is used. One request can cover at most 366 days.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/attendance?employeeId=&from=&to=&page=0&size=100` | Search punches |
| `GET` | `/api/attendance/employees/{employeeId}?from=&to=` | Punches for one employee |
| `GET` | `/api/attendance/summary?employeeId=&from=&to=` | Daily first-in / last-out summary |
| `GET` | `/api/attendance/feed?afterId=0&limit=500` | Incremental feed for downstream systems |
| `POST` | `/api/sync` | Run a sync now (returns 503 where `SYNC_ENABLED=false`) |
| `GET` | `/api/sync/status` | Total records, last synced device id, last run |
| `GET` | `/api/sync/history?limit=20` | Recent sync runs |
| `GET` | `/actuator/health` | Health check (no key needed) |

Example:

```bash
curl -H "X-API-Key: <API_KEY>" "https://<your-service>.onrender.com/api/attendance/summary?from=2026-11-01&to=2026-11-07"
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
├── config/       # Typed configuration, scheduling, API key filter
├── model/        # Records: AttendanceRecord, DevicePunch, SyncRun, DailySummary
├── repository/   # JDBC access to PostgreSQL and the ZKTeco Access database
├── service/      # Sync logic and attendance queries
├── scheduler/    # Cron-triggered sync
└── web/          # REST controllers and error handling
```

## Testing

```bash
./mvnw test
```

The integration tests start a real PostgreSQL 18 container with Testcontainers and mock the ZKTeco source, so Docker must be running. You do not need a database or an `.mdb` file.

## Acknowledgements

This project was inspired by [RumiAust/Zkteco-Attendance-Management-System](https://github.com/RumiAust/Zkteco-Attendance-Management-System).

## License

Released under the [MIT License](LICENSE).
