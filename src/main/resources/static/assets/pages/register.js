import { api, card, currentQuery, downloadCsv, fmtMinutes, fmtTime, h, load, mount, navigate, pageHead, person, replaceQuery, stat, table } from "../core.js";
import { companyToday, dayFlags, dayNote, departmentFilter, STATUS, statusPill } from "../ui.js";

export const page = {
  title: "Daily register",
  roles: ["ADMIN", "HR", "MANAGER"],
  async render(container) {
    const q = currentQuery();
    const state = { date: q.get("date") || companyToday(), departmentId: q.get("departmentId") || "", status: q.get("status") || "" };
    const stats = h("div", { class: "stats" });
    const body = h("div");
    const date = h("input", { type: "date", value: state.date, onchange: () => { if (date.value) { state.date = date.value; refresh(); } } });
    const step = (n) => { const d = new Date(state.date + "T12:00"); d.setDate(d.getDate() + n); state.date = d.toISOString().slice(0, 10); date.value = state.date; refresh(); };
    const status = h("select", { onchange: () => { state.status = status.value; refresh(); } },
      h("option", { value: "" }, "Everyone"),
      [["ABSENT", "Absent"], ["LATE", "Late"], ["PRESENT", "Present"], ["LEAVE", "On leave"], ["ISSUES", "Irregularities"]]
        .map(([v, l]) => h("option", { value: v, selected: v === state.status }, l)));
    const csv = h("button", { class: "btn", type: "button" }, "Export CSV");
    const filters = h("div", { class: "filters" },
      h("button", { class: "btn", type: "button", "aria-label": "Previous day", onclick: () => step(-1) }, "‹"),
      h("label", { class: "field" }, "Date", date),
      h("button", { class: "btn", type: "button", "aria-label": "Next day", onclick: () => step(1) }, "›"),
      await departmentFilter(state.departmentId, (v) => { state.departmentId = v; refresh(); }),
      h("label", { class: "field" }, "Show", status),
      csv);
    mount(container, pageHead("Daily register"), stats, card(null, [filters, body]));
    refresh();

    function refresh() {
      replaceQuery({ date: state.date, departmentId: state.departmentId, status: state.status });
      load(body, () => api.get("/api/attendance/register", { date: state.date, departmentId: state.departmentId }), (rows) => {
        const count = (pred) => rows.filter(pred).length;
        mount(stats,
          stat("Present", count((r) => r.day.status === "PRESENT" || r.day.status === "HALF_DAY"), `of ${rows.length}`, { icon: "check", tone: "green" }),
          stat("Late", count((r) => r.day.lateMinutes > 0), "after grace time", { icon: "clock", tone: "amber" }),
          stat("Absent", count((r) => r.day.status === "ABSENT"), "no punch, no leave", { icon: "xCircle", tone: "red" }),
          stat("On leave", count((r) => r.day.status === "LEAVE"), "approved", { icon: "plane", tone: "blue" }),
          stat("Irregular", count(issue), "missing punch, early or half day", { icon: "alert", tone: "violet" }));
        const shown = rows.filter((r) => {
          switch (state.status) {
            case "ABSENT": return r.day.status === "ABSENT";
            case "LATE": return r.day.lateMinutes > 0;
            case "PRESENT": return r.day.status === "PRESENT" || r.day.status === "HALF_DAY";
            case "LEAVE": return r.day.status === "LEAVE";
            case "ISSUES": return issue(r);
            default: return true;
          }
        });
        csv.onclick = () => downloadCsv(`register-${state.date}.csv`,
          ["Employee ID", "Name", "Department", "Status", "In", "Out", "Worked minutes", "Late minutes", "Early minutes", "Overtime minutes", "Missing punch", "Note"],
          shown.map((r) => [r.employee.employeeCode, r.employee.fullName, r.employee.departmentName, (STATUS[r.day.status] || [r.day.status])[0],
            r.day.firstIn ? fmtTime(r.day.firstIn) : "", r.day.lastOut ? fmtTime(r.day.lastOut) : "", r.day.workedMinutes,
            r.day.lateMinutes, r.day.earlyMinutes, r.day.overtimeMinutes, r.day.missingOut ? "yes" : "", dayNote(r.day) || ""]));
        return table([
          { label: "Employee", value: (r) => person(r.employee.fullName, r.employee.designation, `#/employees/${r.employee.id}`) },
          { label: "Department", value: (r) => r.employee.departmentName || "–" },
          { label: "Status", value: (r) => statusPill(r.day.status) },
          { label: "Shift", value: (r) => `${fmtTime(r.day.scheduledStart)}–${fmtTime(r.day.scheduledEnd)}`, class: "muted" },
          { label: "In", value: (r) => fmtTime(r.day.firstIn) },
          { label: "Out", value: (r) => fmtTime(r.day.lastOut) },
          { label: "Worked", value: (r) => fmtMinutes(r.day.workedMinutes), class: "num" },
          { label: "Notes", value: (r) => [dayNote(r.day), dayFlags(r.day)].filter(Boolean) }
        ], shown, { empty: rows.length ? "Nobody matches this filter." : "No active employees. Add employees first.",
                    onRowClick: (r) => navigate(`/employees/${r.employee.id}`) });
      });
    }
  }
};

const issue = (r) => r.day.missingOut || r.day.earlyMinutes > 0 || r.day.status === "HALF_DAY";
