import { api, card, errorBox, fmtDateTime, fmtTime, h, hasRole, mount, navigate, pageHead, session, stat, table } from "../core.js";
import { balanceTable, companyToday, dayFlags, dayNote, statusPill, summaryStats } from "../ui.js";

export const page = {
  title: "Dashboard",
  async render(container) {
    const lead = hasRole("ADMIN", "HR", "MANAGER");
    const parts = await Promise.allSettled([
      lead ? api.get("/api/attendance/today") : null,
      session.employee ? api.get(`/api/employees/${session.employee.id}/attendance`) : null,
      session.employee ? api.get("/api/leave/balances", { employeeId: session.employee.id }) : null,
      lead ? api.get("/api/leave/requests", { scope: "approvals" }) : null,
      lead ? api.get("/api/corrections", { scope: "approvals" }) : null,
      hasRole("ADMIN", "HR") ? api.get("/api/employees/unmapped-device-ids") : null,
      hasRole("ADMIN", "HR") ? api.get("/api/sync/status") : null
    ]);
    const [today, mine, balances, leaveQ, corrQ, unmapped, sync] = parts.map((p) => (p.status === "fulfilled" ? p.value : null));
    const greeting = `Hello, ${session.me.displayName.split(" ")[0]}`;

    const blocks = [];
    if (!session.employee && !hasRole("ADMIN", "HR")) {
      blocks.push(h("div", { class: "alert info" },
        "Your account is not linked to an employee profile yet, so there is no attendance to show. Ask HR to link it."));
    }

    // ---- pending work
    const pending = (leaveQ?.length || 0) + (corrQ?.length || 0);
    if (lead && pending) {
      blocks.push(h("div", { class: "alert info", style: "display:flex;gap:12px;align-items:center;flex-wrap:wrap" },
        h("strong", null, `${pending} request${pending > 1 ? "s" : ""} waiting for you:`),
        leaveQ?.length ? h("a", { href: "#/leave?tab=approvals" }, `${leaveQ.length} leave`) : null,
        corrQ?.length ? h("a", { href: "#/corrections?tab=approvals" }, `${corrQ.length} punch correction${corrQ.length > 1 ? "s" : ""}`) : null));
    }
    if (unmapped?.length) {
      blocks.push(h("div", { class: "alert info" },
        `${unmapped.length} device ID${unmapped.length > 1 ? "s have" : " has"} punches but no employee profile. `,
        h("a", { href: "#/employees?tab=unmapped" }, "Link them to employees"), " so their attendance is counted."));
    }
    if (sync && hasRole("ADMIN", "HR") && sync.lastRun?.status === "FAILED") {
      blocks.push(errorBox(`The last device sync failed (${fmtDateTime(sync.lastRun.startedAt)}): ${sync.lastRun.message}`));
    }

    // ---- my day
    if (mine) {
      const todayRow = mine.days.find((d) => d.date === companyToday());
      blocks.push(h("div", { class: "two-col" },
        card("My day", h("div", { class: "card-body" },
          todayRow
            ? h("div", { style: "display:grid;gap:8px" },
                h("div", null, statusPill(todayRow.status), " ", dayNote(todayRow) || ""),
                h("div", null, "In: ", h("strong", null, fmtTime(todayRow.firstIn)), "   Out: ", h("strong", null, fmtTime(todayRow.lastOut))),
                h("div", { class: "muted small" }, `Shift ${fmtTime(todayRow.scheduledStart)}–${fmtTime(todayRow.scheduledEnd)} (${mine.schedule.name})`),
                dayFlags(todayRow))
            : h("p", { class: "muted" }, "No working day today.")),
          h("div", { style: "display:flex;gap:8px" },
            h("a", { class: "btn small", href: "#/leave?apply=1" }, "Apply for leave"),
            h("a", { class: "btn small", href: "#/corrections?new=1" }, "Fix a punch"))),
        balances ? card("Leave balance", table(balanceTable(balances), balances)) : null));
      blocks.push(h("h2", { style: "margin:20px 0 10px" }, "This month"), summaryStats(mine.summary));
    }

    // ---- team / company today
    if (today) {
      const scope = hasRole("ADMIN", "HR") ? "Company" : "My team";
      blocks.push(h("h2", { style: "margin:20px 0 10px" }, `${scope} today`),
        h("div", { class: "stats" },
          stat("Present", today.present, `of ${today.employees} employees`),
          stat("Late", today.late, "arrived after grace time"),
          stat("Absent", today.absent, today.notYetIn ? `${today.notYetIn} not due yet` : "no punch, no leave"),
          stat("On leave", today.onLeave),
          stat("Off today", today.off, "holiday or weekly off")));
      const attention = today.rows.filter((r) => r.day.status === "ABSENT" || r.day.lateMinutes > 0 || r.day.status === "HALF_DAY");
      blocks.push(card("Needs attention today", table([
        { label: "Employee", value: (r) => h("a", { href: `#/employees/${r.employee.id}` }, r.employee.fullName) },
        { label: "Department", value: (r) => r.employee.departmentName || "–" },
        { label: "Status", value: (r) => statusPill(r.day.status) },
        { label: "In", value: (r) => fmtTime(r.day.firstIn) },
        { label: "Notes", value: (r) => dayFlags(r.day) }
      ], attention, { empty: "Nobody is absent or late so far." }),
        h("a", { class: "btn small", href: `#/register?date=${today.date}` }, "Full register")));
    }

    if (!blocks.length) blocks.push(h("p", { class: "muted" }, "Nothing to show yet."));
    mount(container, pageHead(greeting), ...blocks);
  }
};

export { navigate };
