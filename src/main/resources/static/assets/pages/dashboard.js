import { api, card, errorBox, fmtDateTime, fmtTime, h, hasRole, icon, mount, person, session, stat, table } from "../core.js";
import { balanceTable, companyToday, dayFlags, dayNote, statusPill, summaryStats } from "../ui.js";

function greeting() {
  const hour = new Date().getHours();
  return hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
}

function longDate(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long", year: "numeric" });
}

const sectionTitle = (iconName, text) => h("div", { class: "section-title" }, icon(iconName, 18), text);

export const page = {
  title: "Dashboard",
  async render(container) {
    const lead = hasRole("ADMIN", "HR", "MANAGER");
    const staff = hasRole("ADMIN", "HR");
    const parts = await Promise.allSettled([
      lead ? api.get("/api/attendance/today") : null,
      session.employee ? api.get(`/api/employees/${session.employee.id}/attendance`) : null,
      session.employee ? api.get("/api/leave/balances", { employeeId: session.employee.id }) : null,
      lead ? api.get("/api/leave/requests", { scope: "approvals" }) : null,
      lead ? api.get("/api/corrections", { scope: "approvals" }) : null,
      staff ? api.get("/api/employees/unmapped-device-ids") : null,
      staff ? api.get("/api/sync/status") : null
    ]);
    const [today, mine, balances, leaveQ, corrQ, unmapped, sync] = parts.map((p) => (p.status === "fulfilled" ? p.value : null));
    const firstName = session.me.displayName.split(" ")[0];

    const heroActions = [];
    if (session.employee) {
      heroActions.push(h("a", { class: "btn", href: "#/corrections?new=1" }, icon("wrench", 16), "Fix a punch"),
                       h("a", { class: "btn primary", href: "#/leave?apply=1" }, icon("leave", 16), "Apply for leave"));
    } else if (lead) {
      heroActions.push(h("a", { class: "btn", href: "#/analytics" }, icon("chart", 16), "Analytics"),
                       h("a", { class: "btn primary", href: `#/register?date=${companyToday()}` }, icon("clipboard", 16), "Today's register"));
    }
    const blocks = [h("section", { class: "hero" },
      h("div", null, h("h1", null, `${greeting()}, ${firstName}`), h("p", null, longDate(companyToday()))),
      heroActions.length ? h("div", { class: "hero-actions" }, heroActions) : null)];

    if (!session.employee && !staff) {
      blocks.push(h("div", { class: "alert info" },
        "Your account is not linked to an employee profile yet, so there is no attendance to show. Ask HR to link it."));
    }

    // ---- pending work and warnings
    const pending = (leaveQ?.length || 0) + (corrQ?.length || 0);
    if (lead && pending) {
      blocks.push(h("div", { class: "alert info", style: "display:flex;gap:14px;align-items:center;flex-wrap:wrap" },
        icon("inbox", 18), h("strong", null, `${pending} request${pending > 1 ? "s" : ""} waiting for your approval`),
        leaveQ?.length ? h("a", { href: "#/leave?tab=approvals" }, `${leaveQ.length} leave →`) : null,
        corrQ?.length ? h("a", { href: "#/corrections?tab=approvals" }, `${corrQ.length} punch correction${corrQ.length > 1 ? "s" : ""} →`) : null));
    }
    if (unmapped?.length) {
      blocks.push(h("div", { class: "alert info", style: "display:flex;gap:10px;align-items:center;flex-wrap:wrap" }, icon("fingerprint", 18),
        h("span", null, `${unmapped.length} device ID${unmapped.length > 1 ? "s have" : " has"} punches but no employee profile. `),
        h("a", { href: "#/employees?tab=unmapped" }, "Link them to employees →")));
    }
    if (sync && staff && sync.lastRun?.status === "FAILED") {
      blocks.push(errorBox(`The last device sync failed (${fmtDateTime(sync.lastRun.startedAt)}): ${sync.lastRun.message}`));
    }

    // ---- team / company today
    if (today) {
      const scope = staff ? "Company today" : "My team today";
      blocks.push(sectionTitle("users", scope),
        h("div", { class: "stats" },
          stat("Present", today.present, `of ${today.employees} employees`, { icon: "check", tone: "green" }),
          stat("Late", today.late, "arrived after grace time", { icon: "clock", tone: "amber" }),
          stat("Absent", today.absent, today.notYetIn ? `${today.notYetIn} not due yet` : "no punch, no leave", { icon: "xCircle", tone: "red" }),
          stat("On leave", today.onLeave, "approved leave", { icon: "plane", tone: "blue" }),
          stat("Off today", today.off, "holiday or weekly off", { icon: "sun", tone: "violet" })));
      const attention = today.rows.filter((r) => r.day.status === "ABSENT" || r.day.lateMinutes > 0 || r.day.status === "HALF_DAY");
      blocks.push(card("Needs attention today", table([
        { label: "Employee", value: (r) => person(r.employee.fullName, r.employee.designation, `#/employees/${r.employee.id}`) },
        { label: "Department", value: (r) => r.employee.departmentName || "–" },
        { label: "Status", value: (r) => statusPill(r.day.status) },
        { label: "In", value: (r) => fmtTime(r.day.firstIn) },
        { label: "Notes", value: (r) => dayFlags(r.day) }
      ], attention, { empty: "Nobody is absent or late so far." }),
        h("a", { class: "btn small", href: `#/register?date=${today.date}` }, "Full register →")));
    }

    // ---- my day
    if (mine) {
      const todayRow = mine.days.find((d) => d.date === companyToday());
      const myDay = todayRow
        ? h("div", { style: "display:grid;gap:14px" },
            h("div", { style: "display:flex;align-items:center;gap:10px;flex-wrap:wrap" }, statusPill(todayRow.status), dayNote(todayRow) || "", dayFlags(todayRow)),
            h("div", { style: "display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px" },
              timeBox("Punched in", fmtTime(todayRow.firstIn)),
              timeBox("Punched out", fmtTime(todayRow.lastOut)),
              timeBox("Shift", `${fmtTime(todayRow.scheduledStart)}–${fmtTime(todayRow.scheduledEnd)}`)),
            h("div", { class: "muted small" }, `Schedule: ${mine.schedule.name}`))
        : h("p", { class: "muted", style: "margin:0" }, "No working day today. Enjoy your time off!");
      blocks.push(sectionTitle("calendar", "My attendance"),
        h("div", { class: "two-col" },
          card("Today", h("div", { class: "card-body" }, myDay), h("a", { class: "btn small", href: "#/my-attendance" }, "My month →")),
          balances ? card("Leave balance", table(balanceTable(balances), balances), h("a", { class: "btn small", href: "#/leave?tab=balance" }, "Details →")) : null),
        h("div", { style: "height:18px" }),
        summaryStats(mine.summary));
    }

    mount(container, ...blocks);
  }
};

function timeBox(label, value) {
  return h("div", { style: "background:var(--surface-2);border-radius:10px;padding:10px 12px" },
    h("div", { class: "muted small" }, label),
    h("div", { style: "font-size:18px;font-weight:700;font-variant-numeric:tabular-nums" }, value));
}
