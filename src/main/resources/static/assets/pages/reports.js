import { api, card, currentQuery, downloadCsv, fmtHours, fmtMinutes, fmtNumber, fmtPercent, h, isoDate, load, mount, navigate, pageHead, replaceQuery, table } from "../core.js";
import { companyToday, departmentFilter } from "../ui.js";

const SORTS = {
  name: ["Name", (a, b) => a.employee.fullName.localeCompare(b.employee.fullName)],
  late: ["Most late arrivals", (a, b) => b.summary.lateDays - a.summary.lateDays || b.summary.lateMinutes - a.summary.lateMinutes],
  absent: ["Most absences", (a, b) => b.summary.absentDays - a.summary.absentDays],
  attendance: ["Lowest attendance", (a, b) => (a.summary.attendancePercent ?? 101) - (b.summary.attendancePercent ?? 101)],
  overtime: ["Most overtime", (a, b) => b.summary.overtimeMinutes - a.summary.overtimeMinutes]
};

export const page = {
  title: "Punctuality report",
  roles: ["ADMIN", "HR", "MANAGER"],
  async render(container) {
    const q = currentQuery();
    const today = companyToday();
    const first = today.slice(0, 8) + "01";
    const state = { from: q.get("from") || first, to: q.get("to") || today, departmentId: q.get("departmentId") || "", sort: SORTS[q.get("sort")] ? q.get("sort") : "late" };
    const from = h("input", { type: "date", value: state.from });
    const to = h("input", { type: "date", value: state.to });
    const sort = h("select", { onchange: () => { state.sort = sort.value; refresh(); } },
      Object.entries(SORTS).map(([k, [l]]) => h("option", { value: k, selected: k === state.sort }, l)));
    const csv = h("button", { class: "btn", type: "button" }, "Export CSV");
    const filters = h("form", { class: "filters" },
      h("label", { class: "field" }, "From", from), h("label", { class: "field" }, "To", to),
      await departmentFilter(state.departmentId, (v) => { state.departmentId = v; refresh(); }),
      h("label", { class: "field" }, "Sort by", sort),
      h("button", { class: "btn primary", type: "submit" }, "Apply"), csv);
    filters.addEventListener("submit", (e) => { e.preventDefault(); state.from = from.value; state.to = to.value; refresh(); });
    const body = h("div");
    mount(container, pageHead("Punctuality report"),
      h("p", { class: "muted", style: "margin-top:-8px" }, "Per-employee totals for the period: late arrivals, early departures, absences and overtime. Days after today are not counted."),
      card(null, [filters, body]));
    refresh();

    function refresh() {
      replaceQuery(state);
      load(body, () => api.get("/api/reports/attendance-summary", { from: state.from, to: state.to, departmentId: state.departmentId }), (rows) => {
        rows.sort(SORTS[state.sort][1]);
        csv.onclick = () => downloadCsv(`punctuality-${state.from}_to_${state.to}.csv`,
          ["Employee ID", "Name", "Department", "Working days", "Present days", "Absent days", "Leave days", "Late days", "Late minutes", "Early departures", "Early minutes", "Missing punches", "Overtime hours", "Attendance %", "On-time %"],
          rows.map(({ employee: e, summary: s }) => [e.employeeCode, e.fullName, e.departmentName, s.workingDays, s.presentDays, s.absentDays,
            s.leaveDays, s.lateDays, s.lateMinutes, s.earlyDays, s.earlyMinutes, s.missingPunchDays, (s.overtimeMinutes / 60).toFixed(2),
            s.attendancePercent ?? "", onTime(s) ?? ""]));
        return table([
          { label: "Employee", value: (r) => r.employee.fullName },
          { label: "Department", value: (r) => r.employee.departmentName || "–" },
          { label: "Attendance", value: (r) => fmtPercent(r.summary.attendancePercent), class: "num" },
          { label: "On time", value: (r) => fmtPercent(onTime(r.summary)), class: "num" },
          { label: "Late days", value: (r) => r.summary.lateDays, class: "num" },
          { label: "Late total", value: (r) => fmtMinutes(r.summary.lateMinutes), class: "num" },
          { label: "Early leaves", value: (r) => r.summary.earlyDays, class: "num" },
          { label: "Absent", value: (r) => fmtNumber(r.summary.absentDays, r.summary.absentDays % 1 ? 1 : 0), class: "num" },
          { label: "Missing punch", value: (r) => r.summary.missingPunchDays, class: "num" },
          { label: "OT hours", value: (r) => fmtHours(r.summary.overtimeMinutes), class: "num" }
        ], rows, { empty: "No employees in this selection.", onRowClick: (r) => navigate(`/employees/${r.employee.id}`) });
      });
    }
  }
};

function onTime(s) {
  const present = Math.ceil(s.presentDays);
  return present ? Math.round((present - s.lateDays) * 1000 / present) / 10 : null;
}

export { isoDate };
