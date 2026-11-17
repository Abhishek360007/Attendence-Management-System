# Attendance Management System

An attendance, leave and payroll system for companies using **ZKTeco biometric devices**. It syncs punches from the devices into **PostgreSQL**, works out who was present, late, absent or on leave, and gives HR, managers and employees a web dashboard and a REST API. It is designed to run on **Render** with a **Neon** serverless Postgres database.

ZKTeco's desktop software (ZKTime / ZKAccess) stores every punch it downloads from the devices in an MS Access database, usually `ATT2000.MDB`. A small sync agent on the office PC copies new punches from that file into PostgreSQL, and everything else is calculated from them.

## Features

**For everyone**
- Personal sign-in with four roles: **Admin**, **HR**, **Manager** and **Employee**.
- **My attendance:** a month calendar, a day-by-day table, totals and CSV export.
- **Leave:** apply, see the working days a request will use, track balances, and cancel.
- **Punch corrections:** ask to add a missed punch, change a wrong time or remove a duplicate.
- **Notifications** in the dashboard, and by email when a mail server is configured.

**Attendance rules**
- Work schedules set start and end times, late and early-departure grace periods, the overtime threshold, the half-day threshold and weekly offs.
- Night shifts that end the next day are supported.
- A schedule applies per department, and can be overridden per employee.
- Company holidays.
- Each day is calculated as **present, half day, absent, on leave, holiday or weekly off**. Late minutes, early departure, overtime and missing punches are worked out at the same time.
- Approved leave, holidays and weekly offs never count as absences.
- Days before tracking started (the first device punch, or a date set in Settings) are not counted.
- Nothing is stored pre-calculated, so changing a schedule or approving a correction updates every report at once.

**For managers**
- **Daily register:** everyone's status for a day.
- **Punctuality report** and **analytics**, covering their own team only.
- **Approvals:** leave and punch corrections for their team, but never their own.

**For HR and admins**
- **Employees:** profiles with department, designation, manager, schedule, salary and joining or leaving dates.
- Device user IDs are mapped to profiles, and a list shows device IDs that are not linked to anyone yet.
- **Payroll:**
  - payable days, unpaid leave, overtime hours
  - base pay, overtime pay and gross pay per month
  - CSV export
- **Analytics:**
  - attendance % by department
  - monthly trends
  - most punctual employees and frequent absentees
  - overtime leaders
  - comparison with the previous month
- **Automated notifications:**
  - daily absence and late alerts for HR and managers
  - a late-arrival alert to the employee
  - the previous day's irregularities
  - weekly and monthly reports by email, with CSV attachments
- **Audit log:** every change records who made it, what changed and when. It cannot be edited.
- Punches are never edited in place. An approved correction keeps the original punch (marked as replaced) next to the corrected one.

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
                 dashboard + REST API (this app, SYNC_ENABLED=false, Render) ◄── browsers, payroll software
```

The same jar runs in both places:
- **On the office PC**, where the `.mdb` file is, it syncs punches straight into Neon.
- **On Render**, it serves the dashboard and the API.

Both can also run the scheduled notification jobs. Each job claims its period in the database first, so nothing is sent twice. Render's free plan sleeps when idle, so the always-on office agent makes sure daily alerts go out on time.

## Tech stack

- **Runtime:** Java 25 (LTS), Spring Boot 4.1 (Spring Framework 7, Spring Security 7, Jackson 3, Tomcat 11)
- **Data access:** Spring JDBC, Spring Session JDBC, Flyway, UCanAccess 5.3 (MS Access JDBC driver)
- **Database:** PostgreSQL 18 (Neon)
- **Frontend:** a vanilla JavaScript dashboard with no build step
- **Tests:** JUnit and Testcontainers
- **Deployment:** Docker on Render, built with Maven 3.9 (wrapper included)

## Deploying

### 1. Create the Neon database

1. Sign in at [neon.tech](https://neon.tech) and create a project. Pick the region closest to your Render region (for example AWS Singapore for Render Singapore).
2. On the project dashboard click **Connect**, turn on **Connection pooling**, and note the host, database, user and password.
3. Build the JDBC URL: `jdbc:postgresql://<host>/<database>?sslmode=require`

Tables are created and upgraded automatically on start by Flyway migrations in [`db/migration`](src/main/resources/db/migration).

### 2. Deploy to Render

1. Sign in at [render.com](https://render.com) with GitHub, click **New → Blueprint** and pick this repository. Render reads [`render.yaml`](render.yaml) and builds the [`Dockerfile`](Dockerfile).
2. Fill in `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` from Neon. Set `PUBLIC_URL` to the service address once you know it.
3. Open the service URL. The first visit shows **Welcome**: enter the `API_KEY` from the service's **Environment** tab (it proves you own the installation) and create the first admin account.
4. In the dashboard:
   1. Set the company name and time zone under **Settings**.
   2. Create departments, schedules and holidays under **Departments & schedules**.
   3. Add employees with their device user IDs, or create them from **Employees → Unlinked device IDs**.
   4. Create sign-ins for the people who need them.

On the free plan the service sleeps after about 15 minutes without traffic, so the first request after that takes around a minute. Sign-ins are stored in the database, so nobody is signed out by restarts or redeploys.

### 3. Run the sync agent on the office PC

On the Windows PC that runs ZKTime / ZKAccess, copy or clone this repository, open PowerShell in its `agent` folder, and run [`install-agent.ps1`](agent/install-agent.ps1). It installs Java 25 with winget if needed, builds the jar, and installs a Windows service that starts with Windows and restarts itself after a crash.

**1. Find the database file.** ZKTime / ZKAccess keeps it in its install folder, usually as `att2000.mdb`, for example `C:\Program Files (x86)\ZKTeco\att2000.mdb`.

**2. Check your settings.** This runs one sync and reports the result, installs nothing, and doesn't need Administrator rights. You are asked for the database password.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\install-agent.ps1 -MdbPath "C:\Program Files (x86)\ZKTeco\att2000.mdb" `
    -DbUrl "jdbc:postgresql://<neon-host>/<database>?sslmode=require" `
    -DbUsername "<neon-user>" -TestOnly
```

**3. Install the service.** Run the same command without `-TestOnly` in an Administrator PowerShell.

About the installed service:
- It's called **Attendance Sync Agent** (`AttendanceAgent`) and lives in `C:\AttendanceAgent`. Only Administrators can read that folder, because its configuration holds the database password.
- Logs are in `C:\AttendanceAgent\logs`.
- The agent's own dashboard is at http://127.0.0.1:9010/, reachable on that PC only.
- To update it, pull the latest code, delete `target\`, and run the installer again.
- To remove it, run `.\uninstall-agent.ps1` as Administrator.

If the `.mdb` file is on another computer, use its UNC path (`\\PC-NAME\share\att2000.mdb`, not a mapped drive letter) and add `-ServiceAccount (Get-Credential)` with a Windows account that can read that share.

The agent detects the database layout on its first sync:
- If `CHECKINOUT` has an `Auto_increment` column, new punches are found by that number.
- Otherwise (the standard ZKTime layout), they are found by `CHECKTIME`, and the last 7 days are re-read on every run. That catches punches ZKTime downloads from the devices late. Change the window with `SYNC_LOOKBACK_DAYS`.

### 4. Email (optional)

Without email, notifications only appear in the dashboard. To send email, set these variables on Render. To have daily alerts emailed while Render sleeps, also pass them to the agent installer, for example `-Environment @{ MAIL_HOST = "smtp.gmail.com"; MAIL_USERNAME = "you@gmail.com"; MAIL_PASSWORD = "<app password>"; PUBLIC_URL = "https://<your-service>.onrender.com" }`.

| Variable | Gmail example |
|---|---|
| `MAIL_HOST` | `smtp.gmail.com` |
| `MAIL_PORT` | `587` |
| `MAIL_USERNAME` | `you@gmail.com` |
| `MAIL_PASSWORD` | an [app password](https://support.google.com/accounts/answer/185833) |
| `PUBLIC_URL` | `https://<your-service>.onrender.com` (for links in emails) |

## Roles

| Can… | Employee | Manager | HR | Admin |
|---|:-:|:-:|:-:|:-:|
| See own attendance, apply for leave, request corrections | ✓ | ✓ | ✓ | ✓ |
| See team attendance, register, reports and analytics | | team | everyone | everyone |
| Approve leave and corrections (never their own) | | team | everyone | everyone |
| Manage employees, departments, schedules, holidays, leave types | | | ✓ | ✓ |
| Payroll, salaries, audit log, raw punches | | | ✓ | ✓ |
| Users, roles and company settings | | | | ✓ |

A manager's team is everyone who reports to them directly, plus everyone in the departments they head.

## How things are calculated

- **Which day a punch belongs to:** each working day's window opens 4 hours before the shift and lasts 24 hours. The first punch is *in*, the last is *out*.
- **Late:** the first punch is after start + grace. Lateness is counted from the shift start.
- **Early departure:** the last punch is before end − grace. Judged only once the shift is over.
- **Overtime:** time worked beyond the scheduled hours, once it reaches the threshold. All work on holidays and weekly offs counts as overtime.
- **Half day:** worked fewer minutes than the half-day threshold, with both an in and an out punch.
- **Missing punch:** only one punch on a day that is over.
- **Attendance %:** present days ÷ (working days − leave days). A half day counts as half.
- **Payable days:** days employed in the month − absent days − unpaid leave days. Weekly offs, holidays and paid leave are paid.
- **Pay:** base = monthly salary ÷ days in month × payable days. Overtime = (salary ÷ scheduled hours in the month) × overtime hours × the multiplier in Settings (default 1.5).

## Configuration

| Variable | Description | Default |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL connection | `jdbc:postgresql://localhost:5432/attendance`, `postgres`, *(empty)* |
| `DB_POOL_SIZE` | Maximum database connections | `5` |
| `API_KEY` | Machine access via `X-API-Key`, and the one-time setup key | *(empty)* |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | Create this admin on start when there are no users (skips the setup screen) | *(empty)* |
| `SESSION_TIMEOUT` | Sign-in lifetime | `12h` |
| `ZKTECO_MDB_PATH` | Path to the ZKTeco Access database | `C:/ZKTeco/ATT2000.MDB` |
| `SYNC_ENABLED` | Run the device sync (`false` on Render) | `true` (the Docker image sets `false`) |
| `SYNC_CRON` | Sync schedule (Spring cron, 6 fields) | `0 */1 * * * *` |
| `SYNC_BATCH_SIZE` | Maximum punches read per run | `5000` |
| `SYNC_LOOKBACK_DAYS` | Days re-read each run when the database has no `Auto_increment` column | `7` |
| `NOTIFICATIONS_ENABLED` | Run daily alerts and weekly or monthly reports | `true` |
| `PUBLIC_URL` | Dashboard address for links in emails | *(empty)* |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | SMTP server | *(email off)* |
| `PORT` / `SERVER_PORT` | HTTP port (`PORT` is set by Render) | `9010` |

These company settings are changed in the dashboard under **Settings**:
- company name and time zone
- overtime multiplier
- daily alert time
- extra report recipients
- late alerts to employees
- the tracking start date

## REST API

The dashboard uses the same API. Signed-in users send their session cookie and the CSRF token. Machine clients send `X-API-Key: <API_KEY>`, which gives read access to attendance, reports, analytics and payroll, and lets them trigger a sync.

Highlights:

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/attendance/today` | Today's counts and statuses for the caller's scope |
| `GET` | `/api/attendance/register?date=&departmentId=` | Everyone's calculated day |
| `GET` | `/api/employees/{id}/attendance?month=2026-11` | One employee's days and totals |
| `GET` | `/api/reports/attendance-summary?from=&to=&departmentId=` | Per-employee punctuality and absence totals |
| `GET` | `/api/analytics?month=&months=6&departmentId=` | KPIs, trends, departments, rankings |
| `GET` | `/api/payroll?month=` · `/api/payroll/export?month=` | Payroll as JSON or CSV |
| `GET` | `/api/attendance/feed?afterId=0&limit=500` | Incremental raw punch feed (pass the last `id` you received) |
| `GET` | `/api/sync/status` · `POST /api/sync` | Sync status, or run a sync now (503 on Render) |
| | `/api/employees`, `/api/departments`, `/api/schedules`, `/api/holidays` | Organization data |
| | `/api/leave/...`, `/api/corrections/...` | Requests, approvals and balances |
| `GET` | `/api/audit?entityType=&entityId=` | Audit log |
| `GET` | `/actuator/health` | Health check (no sign-in) |

```bash
curl -H "X-API-Key: <API_KEY>" "https://<your-service>.onrender.com/api/payroll/export?month=2026-10" -o payroll.csv
```

## Project structure

```
src/main/java/com/abhishek/attendance
├── auth/         # Users, roles, sign-in, login throttling
├── audit/        # Append-only audit log
├── settings/     # Company settings (time zone, overtime multiplier, alerts)
├── org/          # Employees, departments, schedules, holidays, access rules
├── engine/       # Turns punches into days: status, late, early, overtime
├── leave/        # Leave types, requests, approvals, balances
├── correction/   # Punch correction requests and approvals
├── payroll/      # Monthly pay calculation and CSV export
├── analytics/    # KPIs, trends and rankings
├── notify/       # In-app and email notifications, scheduled alerts and reports
├── service/, repository/, scheduler/   # ZKTeco sync
├── config/       # Security, API key, scheduling
└── web/          # Raw punch endpoints and error handling

src/main/resources
├── db/migration/        # Flyway migrations
├── static/              # Dashboard (index.html, assets/*.js, assets/app.css)
└── application.properties

agent/                   # Windows service installer for the office-PC sync agent
```

## Testing

```bash
./mvnw test
```

The test suite has 44 tests:
- **Unit tests for the attendance engine:** grace periods, overtime, half days, night shifts, and judging today while the day is still in progress.
- **Access-database tests:** queries against real Access files built in both ZKTeco layouts.
- **Integration tests** against a real PostgreSQL 18 container (Testcontainers), covering:
  - sign-in, roles and CSRF
  - employee scoping
  - leave balances and approvals
  - corrections and the audit trail
  - payroll figures checked against a hand calculation
  - analytics and notifications

Docker must be running to run them.

## Acknowledgements

This project was inspired by [RumiAust/Zkteco-Attendance-Management-System](https://github.com/RumiAust/Zkteco-Attendance-Management-System).

## License

Released under the [MIT License](LICENSE).
