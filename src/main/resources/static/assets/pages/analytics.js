import { api, card, currentQuery, fmtMonth, fmtNumber, fmtPercent, h, load, mount, monthISO, navigate, pageHead, person, replaceQuery, stat, table } from "../core.js";
import { barList, lineChart, tableView } from "../charts.js";
import { departmentFilter, monthInput } from "../ui.js";

const shortMonth = (ym) => {
  const [y, m] = ym.split("-").map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(undefined, { month: "short" });
};

/** "+2.1 pts" style change vs previous month; `better` says which direction is good. */
function delta(now, before, { unit = "", better = "up", digits = 1 } = {}) {
  if (now === null || now === undefined || before === null || before === undefined) return h("span", { class: "delta flat" }, "no previous data");
  const diff = now - before;
  if (Math.abs(diff) < 0.05) return h("span", { class: "delta flat" }, "same as last month");
  const good = better === "up" ? diff > 0 : diff < 0;
  return h("span", { class: `delta ${better === "flat" ? "flat" : good ? "up" : "down"}` }, `${diff > 0 ? "▲" : "▼"} ${fmtNumber(Math.abs(diff), digits)}${unit} vs last month`);
}

export const page = {
  title: "Analytics",
  roles: ["ADMIN", "HR", "MANAGER"],
  async render(container) {
    const q = currentQuery();
    const state = { month: q.get("month") || monthISO(), months: Number(q.get("months")) || 6, departmentId: q.get("departmentId") || "" };
    const range = h("select", { onchange: () => { state.months = Number(range.value); refresh(); } },
      [3, 6, 12].map((n) => h("option", { value: n, selected: n === state.months }, `${n} months`)));
    const filters = h("div", { class: "filters", style: "padding:0 0 16px;border:0" },
      monthInput(state.month, (m) => { state.month = m; refresh(); }),
      h("label", { class: "field" }, "Trend over", range),
      await departmentFilter(state.departmentId, (v) => { state.departmentId = v; refresh(); }));
    const body = h("div");
    mount(container, pageHead("Analytics"), filters, body);
    refresh();

    function refresh() {
      replaceQuery({ month: state.month, months: state.months, departmentId: state.departmentId });
      load(body, () => api.get("/api/analytics", state), (r) => {
        const c = r.current, p = r.previous;
        const trendPoints = r.trend.map((t) => ({ label: shortMonth(t.month), value: t.kpis.attendancePercent }));
        const latePoints = r.trend.map((t) => ({ label: shortMonth(t.month), value: t.kpis.employees ? t.kpis.lateArrivals : null }));
        const people = (list, value, empty) => table([
          { label: "Employee", value: (e) => person(e.fullName, null, `#/employees/${e.employeeId}`) },
          { label: "Department", value: (e) => e.departmentName || "–" },
          ...value
        ], list, { empty, onRowClick: (e) => navigate(`/employees/${e.employeeId}`) });
        return [
          h("div", { class: "stats" },
            stat("Attendance", fmtPercent(c.attendancePercent), delta(c.attendancePercent, p.attendancePercent, { unit: " pts" }), { icon: "check", tone: "green" }),
            stat("On time", fmtPercent(c.punctualityPercent), delta(c.punctualityPercent, p.punctualityPercent, { unit: " pts" }), { icon: "clock", tone: "blue" }),
            stat("Late arrivals", c.lateArrivals, delta(c.lateArrivals, p.lateArrivals, { better: "down", digits: 0 }), { icon: "alert", tone: "amber" }),
            stat("Absent days", fmtNumber(c.absentDays, c.absentDays % 1 ? 1 : 0), delta(c.absentDays, p.absentDays, { better: "down" }), { icon: "xCircle", tone: "red" }),
            stat("Overtime", `${fmtNumber(c.overtimeHours, 1)} h`, delta(c.overtimeHours, p.overtimeHours, { unit: " h", better: "flat" }), { icon: "hourglass", tone: "violet" }),
            stat("Avg. day", c.avgWorkedHoursPerDay === null ? "–" : `${fmtNumber(c.avgWorkedHoursPerDay, 1)} h`, `${c.employees} employees, ${fmtMonth(state.month)}`, { icon: "trendUp", tone: "slate" })),
          h("div", { class: "two-col" },
            card("Attendance % by month", [
              lineChart(trendPoints, { yMax: 100, format: (v) => `${Math.round(v)}%`, ariaLabel: "Attendance percentage by month" }),
              tableView(["Month", "Attendance", "Late arrivals", "Absent days", "Overtime h"],
                r.trend.map((t) => [fmtMonth(t.month), fmtPercent(t.kpis.attendancePercent), t.kpis.lateArrivals,
                  fmtNumber(t.kpis.absentDays, 1), fmtNumber(t.kpis.overtimeHours, 1)]))]),
            card("Late arrivals by month", [
              lineChart(latePoints, { format: (v) => fmtNumber(v), ariaLabel: "Late arrivals by month" }),
              tableView(["Month", "Late arrivals", "Late minutes"],
                r.trend.map((t) => [fmtMonth(t.month), t.kpis.lateArrivals, t.kpis.lateMinutes]))])),
          h("div", { style: "height:16px" }),
          card(`Attendance by department, ${fmtMonth(state.month)}`, r.departments.length ? [
            barList(r.departments.map((d) => ({ label: d.department, value: d.kpis.attendancePercent,
              title: `${d.department}: ${d.kpis.employees} employees, ${d.kpis.lateArrivals} late arrivals, ${fmtNumber(d.kpis.absentDays, 1)} absent days` })),
              { max: 100, format: (v) => fmtPercent(v) }),
            tableView(["Department", "Employees", "Attendance", "On time", "Late arrivals", "Absent days", "Overtime h"],
              r.departments.map((d) => [d.department, d.kpis.employees, fmtPercent(d.kpis.attendancePercent), fmtPercent(d.kpis.punctualityPercent),
                d.kpis.lateArrivals, fmtNumber(d.kpis.absentDays, 1), fmtNumber(d.kpis.overtimeHours, 1)]))
          ] : h("div", { class: "empty" }, "No attendance in this month.")),
          h("div", { style: "height:16px" }),
          h("div", { class: "two-col" },
            card("Most punctual", people(r.mostPunctual, [
              { label: "On time", value: (e) => fmtPercent(e.onTimePercent), class: "num" },
              { label: "Days", value: (e) => fmtNumber(e.presentDays, e.presentDays % 1 ? 1 : 0), class: "num" }
            ], "Needs at least 5 days present in the month.")),
            card("Frequent absences", people(r.frequentAbsentees, [
              { label: "Absent days", value: (e) => fmtNumber(e.absentDays, e.absentDays % 1 ? 1 : 0), class: "num" },
              { label: "Late days", value: (e) => e.lateDays, class: "num" }
            ], "Nobody was absent."))),
          h("div", { style: "height:16px" }),
          card("Most overtime", people(r.overtimeLeaders, [
            { label: "Overtime", value: (e) => `${fmtNumber(e.overtimeHours, 1)} h`, class: "num" },
            { label: "Days present", value: (e) => fmtNumber(e.presentDays, e.presentDays % 1 ? 1 : 0), class: "num" }
          ], "No overtime this month."))
        ];
      });
    }
  }
};
