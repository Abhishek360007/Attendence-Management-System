import {
  api, card, currentQuery, downloadCsv, fmtDate, fmtDateTime, h, hasRole, load, mount, navigate, pageHead, person, pill,
  replaceQuery, table, tabs
} from "../core.js";
import { departmentFilter } from "../ui.js";
import { employeeDialog } from "./employee-form.js";

export const page = {
  title: "Employees",
  roles: ["ADMIN", "HR", "MANAGER"],
  async render(container) {
    const q = currentQuery();
    const staff = hasRole("ADMIN", "HR");
    const state = {
      tab: staff && q.get("tab") === "unmapped" ? "unmapped" : "list",
      q: q.get("q") || "", departmentId: q.get("departmentId") || "", status: q.get("status") || "active"
    };
    const tabBar = h("div");
    const body = h("div");
    mount(container,
      pageHead(staff ? "Employees" : "My team",
        staff ? h("button", { class: "btn primary", type: "button", onclick: () => employeeDialog(null, {}, (e) => navigate(`/employees/${e.id}`)) }, "Add employee") : null),
      card(null, [tabBar, body]));
    show();

    function show() {
      replaceQuery({ tab: state.tab === "list" ? "" : state.tab, q: state.q, departmentId: state.departmentId,
                     status: state.status === "active" ? "" : state.status });
      mount(tabBar, staff ? tabs([["list", "Employees"], ["unmapped", "Unlinked device IDs"]], state.tab, (k) => { state.tab = k; show(); }) : null);
      if (state.tab === "unmapped") return unmapped();
      list();
    }

    async function list() {
      const search = h("input", { type: "search", placeholder: "Name, ID or designation", value: state.q, size: "24" });
      const status = h("select", null, [["active", "Active"], ["inactive", "Deactivated"], ["all", "All"]]
        .map(([v, l]) => h("option", { value: v, selected: v === state.status }, l)));
      const filters = h("form", { class: "filters" },
        h("label", { class: "field" }, "Search", search),
        await departmentFilter(state.departmentId, (v) => { state.departmentId = v; show(); }),
        h("label", { class: "field" }, "Status", status),
        h("button", { class: "btn primary", type: "submit" }, "Apply"));
      filters.addEventListener("submit", (e) => { e.preventDefault(); state.q = search.value.trim(); state.status = status.value; show(); });
      const results = h("div");
      mount(body, filters, results);
      load(results, () => api.get("/api/employees", {
        q: state.q, departmentId: state.departmentId,
        active: state.status === "all" ? "" : state.status === "active"
      }), (rows) => [
        table([
          { label: "Name", value: (e) => person(e.fullName, e.designation, `#/employees/${e.id}`) },
          { label: "Employee ID", value: (e) => e.employeeCode },
          { label: "Device ID", value: (e) => e.deviceUserId || h("span", { class: "muted" }, "not linked") },
          { label: "Department", value: (e) => e.departmentName || "–" },
          { label: "Reports to", value: (e) => e.managerName || "–" },
          { label: "Sign-in", value: (e) => (e.userEmail ? pill(e.userRole === "EMPLOYEE" ? "Yes" : e.userRole, "ok") : pill("None", "neutral")) },
          { label: "Status", value: (e) => (e.active ? pill("Active", "ok") : pill("Deactivated", "neutral")) }
        ], rows, { empty: "No employees match. Add employees, or link device IDs from the Unlinked device IDs tab.",
                   onRowClick: (e) => navigate(`/employees/${e.id}`) }),
        rows.length ? h("div", { class: "pager" }, `${rows.length} employee(s)`,
          h("button", { class: "btn small", type: "button", onclick: () => exportCsv(rows) }, "Export CSV")) : null
      ]);
    }

    function unmapped() {
      mount(body, h("p", { class: "muted", style: "margin:12px 16px 0" },
        "These device user IDs have punches but no employee profile, so their attendance is not counted. Create a profile for each person."));
      const results = h("div");
      body.append(results);
      load(results, () => api.get("/api/employees/unmapped-device-ids"), (rows) => table([
        { label: "Device ID", value: (r) => h("strong", null, r.deviceUserId) },
        { label: "Name on device", value: (r) => r.deviceName || "–" },
        { label: "Punches", value: (r) => r.punches, class: "num" },
        { label: "Last punch", value: (r) => fmtDateTime(r.lastPunch) },
        { label: "", value: (r) => h("button", { class: "btn small primary", type: "button",
            onclick: () => employeeDialog(null, { fullName: r.deviceName || "", deviceUserId: r.deviceUserId, employeeCode: r.deviceUserId },
              (e) => navigate(`/employees/${e.id}`)) }, "Create employee") }
      ], rows, { empty: "Every device ID with punches is linked to an employee." }));
    }

    function exportCsv(rows) {
      downloadCsv("employees.csv",
        ["Employee ID", "Name", "Device ID", "Department", "Designation", "Reports to", "Email", "Phone", "Joined", "Left", "Active"],
        rows.map((e) => [e.employeeCode, e.fullName, e.deviceUserId, e.departmentName, e.designation, e.managerName,
          e.email, e.phone, e.joinedOn, e.leftOn, e.active ? "yes" : "no"]));
    }
  }
};

export { fmtDate };
