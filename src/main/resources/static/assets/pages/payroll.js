import { api, card, currentQuery, fmtHours, fmtMonth, fmtNumber, h, load, mount, monthISO, pageHead, person, replaceQuery, stat, table } from "../core.js";
import { departmentFilter, monthInput, shiftMonth } from "../ui.js";

export const page = {
  title: "Payroll",
  roles: ["ADMIN", "HR"],
  async render(container) {
    const q = currentQuery();
    const state = { month: q.get("month") || shiftMonth(monthISO(), -1), departmentId: q.get("departmentId") || "" };
    const stats = h("div", { class: "stats" });
    const body = h("div");
    const download = h("a", { class: "btn", download: "" }, "Download CSV");
    const filters = h("div", { class: "filters" },
      monthInput(state.month, (m) => { state.month = m; refresh(); }),
      await departmentFilter(state.departmentId, (v) => { state.departmentId = v; refresh(); }),
      download);
    mount(container,
      pageHead("Payroll"),
      h("p", { class: "muted", style: "margin-top:-8px" },
        "Payable days = days employed − absent days − unpaid leave (weekly offs, holidays and paid leave are paid; a half day is half absent). ",
        "Base pay = salary ÷ days in month × payable days. Overtime pay = hourly rate × overtime hours × the multiplier in Settings."),
      stats, card(null, [filters, body]));
    refresh();

    function refresh() {
      replaceQuery({ month: state.month, departmentId: state.departmentId });
      download.href = `/api/payroll/export?month=${state.month}${state.departmentId ? "&departmentId=" + state.departmentId : ""}`;
      download.setAttribute("download", `payroll-${state.month}.csv`);
      load(body, () => api.get("/api/payroll", { month: state.month, departmentId: state.departmentId }), (p) => {
        const noSalary = p.rows.filter((r) => r.monthlySalary === null).length;
        mount(stats,
          stat("Gross pay", fmtNumber(p.totalGross, 2), fmtMonth(state.month), { icon: "money", tone: "green" }),
          stat("Employees", p.rows.length, noSalary ? `${noSalary} without a salary` : "all with a salary", { icon: "users", tone: "blue" }),
          stat("Overtime", `${fmtHours(p.rows.reduce((a, r) => a + r.overtimeMinutes, 0))} h`, `paid at ×${p.overtimeMultiplier}`, { icon: "hourglass", tone: "violet" }),
          stat("Unpaid days", fmtNumber(p.rows.reduce((a, r) => a + r.absentDays + r.unpaidLeaveDays, 0), 1), "absence + unpaid leave", { icon: "xCircle", tone: "red" }));
        return [
          p.complete ? null : h("div", { class: "alert info", style: "margin:12px 16px" },
            "This month is not over yet: days still to come are counted as payable. Run payroll after the month ends."),
          table([
            { label: "Employee", value: (r) => person(r.fullName, r.employeeCode, `#/employees/${r.employeeId}`) },
            { label: "Department", value: (r) => r.departmentName || "–" },
            { label: "Salary", value: (r) => (r.monthlySalary === null ? h("span", { class: "muted" }, "not set") : fmtNumber(r.monthlySalary, 2)), class: "num" },
            { label: "Working", value: (r) => r.workingDays, class: "num" },
            { label: "Present", value: (r) => fmtNumber(r.presentDays, r.presentDays % 1 ? 1 : 0), class: "num" },
            { label: "Paid leave", value: (r) => r.paidLeaveDays, class: "num" },
            { label: "Unpaid leave", value: (r) => r.unpaidLeaveDays, class: "num" },
            { label: "Absent", value: (r) => fmtNumber(r.absentDays, r.absentDays % 1 ? 1 : 0), class: "num" },
            { label: "Late", value: (r) => r.lateDays, class: "num" },
            { label: "OT hours", value: (r) => fmtHours(r.overtimeMinutes), class: "num" },
            { label: "Payable days", value: (r) => h("strong", null, fmtNumber(r.payableDays, r.payableDays % 1 ? 1 : 0)), class: "num" },
            { label: "Base pay", value: (r) => (r.basePay === null ? "–" : fmtNumber(r.basePay, 2)), class: "num" },
            { label: "OT pay", value: (r) => (r.overtimePay === null ? "–" : fmtNumber(r.overtimePay, 2)), class: "num" },
            { label: "Gross", value: (r) => (r.grossPay === null ? "–" : h("strong", null, fmtNumber(r.grossPay, 2))), class: "num" }
          ], p.rows, { empty: "No employees were employed in this month." })
        ];
      });
    }
  }
};
