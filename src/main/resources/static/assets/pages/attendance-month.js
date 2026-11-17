// Month view of one employee's attendance: summary tiles, calendar, day table and leave balances.
import { api, card, downloadCsv, fmtMinutes, fmtMonth, fmtTime, h, load, mount, monthISO, table, tabs } from "../core.js";
import { balanceTable, calendar, dayNote, dayTable, monthInput, shiftMonth, STATUS, summaryStats } from "../ui.js";

export function attendanceMonth(employeeId, initialMonth, { showBalances = true, onMonth } = {}) {
  const root = h("div");
  let month = initialMonth || monthISO();
  let view = "calendar";
  render();
  return root;

  function render() {
    const body = h("div");
    const nav = h("div", { class: "filters", style: "padding:0 0 12px" },
      h("button", { class: "btn", type: "button", "aria-label": "Previous month", onclick: () => go(shiftMonth(month, -1)) }, "‹"),
      monthInput(month, go),
      h("button", { class: "btn", type: "button", "aria-label": "Next month", onclick: () => go(shiftMonth(month, 1)) }, "›"));
    mount(root, nav, body);
    load(body, () => Promise.all([
      api.get(`/api/employees/${employeeId}/attendance`, { month }),
      showBalances ? api.get("/api/leave/balances", { employeeId, year: month.slice(0, 4) }).catch(() => null) : null
    ]), ([data, balances]) => {
      const exportBtn = h("button", { class: "btn small", type: "button", onclick: () => exportCsv(data) }, "Export CSV");
      const viewBody = h("div", { class: "card-body" });
      const tabBar = h("div");
      const showView = () => {
        mount(tabBar, tabs([["calendar", "Calendar"], ["days", "Day by day"]], view, (k) => { view = k; showView(); }));
        mount(viewBody, view === "calendar"
          ? calendar(month, data.days)
          : table(dayTable(data.days), [...data.days].reverse(), { empty: "No days in this month." }));
      };
      showView();
      return [
        summaryStats(data.summary),
        h("p", { class: "muted small", style: "margin:-6px 0 12px" },
          `Schedule: ${data.schedule.name}, ${data.schedule.startTime.slice(0, 5)}–${data.schedule.endTime.slice(0, 5)}, `,
          `grace ${data.schedule.lateGraceMinutes} min. Totals count days up to today.`),
        card(fmtMonth(month), [tabBar, viewBody], exportBtn),
        balances ? h("div", { style: "margin-top:16px" }, card(`Leave balance ${month.slice(0, 4)}`, table(balanceTable(balances), balances))) : null
      ];
    });
  }

  function go(m) {
    month = m;
    onMonth?.(m);
    render();
  }

  function exportCsv(data) {
    downloadCsv(`attendance-${data.employee.employeeCode}-${month}.csv`,
      ["Date", "Status", "In", "Out", "Worked minutes", "Late minutes", "Early minutes", "Overtime minutes", "Missing punch", "Note"],
      data.days.map((d) => [d.date, (STATUS[d.status] || [d.status])[0], d.firstIn ? fmtTime(d.firstIn) : "",
        d.lastOut ? fmtTime(d.lastOut) : "", d.workedMinutes, d.lateMinutes, d.earlyMinutes, d.overtimeMinutes,
        d.missingOut ? "yes" : "", dayNote(d) || ""]));
  }
}

export { fmtMinutes };
