import {
  api, card, currentQuery, fmtDate, fmtDateTime, fmtNumber, h, hasRole, load, mount, pageHead, pill, replaceQuery,
  table, tabs, titleCase
} from "../core.js";
import { requestPill } from "../ui.js";
import { attendanceMonth } from "./attendance-month.js";
import { employeeDialog, loginDialog } from "./employee-form.js";
import { correctionDialog } from "./corrections.js";
import { applyDialog } from "./leave.js";

export const page = {
  title: "Employee",
  roles: ["ADMIN", "HR", "MANAGER"],
  async render(container, params) {
    const id = Number(params.id);
    const staff = hasRole("ADMIN", "HR");
    let tab = currentQuery().get("tab") || "attendance";
    let month = currentQuery().get("month") || undefined;

    await load(container, () => api.get(`/api/employees/${id}`), (e) => {
      const tabBar = h("div");
      const body = h("div", { class: "card-body" });
      const detail = (label, value) => h("div", null, h("div", { class: "muted small" }, label), h("div", null, value ?? "–"));
      const actions = [
        h("button", { class: "btn", type: "button", onclick: () => correctionDialog({ employee: e, onSaved: () => show() }) }, "Correct a punch"),
        staff ? h("button", { class: "btn", type: "button", onclick: () => applyDialog({ employee: e, onSaved: () => show() }) }, "Record leave") : null,
        staff && !e.userId && e.active ? h("button", { class: "btn", type: "button", onclick: () => loginDialog(e, () => page.render(container, params)) }, "Create sign-in") : null,
        staff ? h("button", { class: "btn primary", type: "button", onclick: () => employeeDialog(e, {}, () => page.render(container, params)) }, "Edit") : null
      ];
      const show = () => {
        replaceQuery({ tab: tab === "attendance" ? "" : tab, month });
        mount(tabBar, tabs([["attendance", "Attendance"], ["leave", "Leave"], ["punches", "Punch history"]], tab, (k) => { tab = k; show(); }));
        if (tab === "attendance") mount(body, attendanceMonth(id, month, { onMonth: (m) => { month = m; replaceQuery({ month: m }); } }));
        else if (tab === "leave") leaveTab(body, id);
        else punchesTab(body, id);
      };
      setTimeout(show);
      return [
        pageHead(e.fullName, ...actions),
        !e.active ? h("div", { class: "alert info" }, `Deactivated${e.leftOn ? ", left on " + fmtDate(e.leftOn) : ""}.`) : null,
        !e.deviceUserId ? h("div", { class: "alert info" }, "No device user ID: punches from the ZKTeco devices are not linked to this employee. Edit the profile to add it.") : null,
        card(null, h("div", { class: "card-body form-grid" },
          detail("Employee ID", e.employeeCode),
          detail("Device user ID", e.deviceUserId),
          detail("Department", e.departmentName),
          detail("Designation", e.designation),
          detail("Reports to", e.managerName ? h("a", { href: `#/employees/${e.managerId}` }, e.managerName) : "Department head"),
          detail("Work schedule", e.workScheduleName || "Department's schedule"),
          detail("Email", e.email),
          detail("Phone", e.phone),
          detail("Joined", e.joinedOn ? fmtDate(e.joinedOn) : null),
          staff ? detail("Monthly salary", e.monthlySalary != null ? fmtNumber(e.monthlySalary, 2) : null) : null,
          detail("Sign-in", e.userEmail ? `${e.userEmail} (${titleCase(e.userRole)})` : "None"))),
        h("div", { style: "height:16px" }),
        card(null, [tabBar, body])
      ];
    });
  }
};

function leaveTab(body, id) {
  load(body, () => api.get("/api/leave/requests", { scope: "all" }).then((rows) => rows.filter((r) => r.employeeId === id)),
    (rows) => table([
      { label: "Type", value: (r) => r.leaveTypeName },
      { label: "From", value: (r) => fmtDate(r.startDate) },
      { label: "To", value: (r) => fmtDate(r.endDate) },
      { label: "Days", value: (r) => fmtNumber(r.days, 1), class: "num" },
      { label: "Status", value: (r) => requestPill(r.status) },
      { label: "Decided by", value: (r) => r.decidedByName || "–" },
      { label: "Reason", value: (r) => r.reason || "–", class: "wrap" }
    ], rows, { empty: "No leave requests." }));
}

function punchesTab(body, id) {
  const to = new Date();
  const from = new Date(to.getFullYear(), to.getMonth(), to.getDate() - 30);
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  load(body, () => api.get(`/api/employees/${id}/punches`, { from: iso(from), to: iso(to) }), (rows) => [
    h("p", { class: "muted small", style: "margin-top:0" }, "Last 30 days, newest first. Corrected punches stay listed, struck through, so the original record is never lost."),
    table([
      { label: "Time", value: (p) => (p.voided ? h("s", { class: "muted" }, fmtDateTime(p.checkTime)) : fmtDateTime(p.checkTime)) },
      { label: "Source", value: (p) => (p.source === "MANUAL" ? pill("Correction", "info") : "Device") },
      { label: "State", value: (p) => (p.voided ? pill("Replaced / removed", "neutral") : pill("Counted", "ok")) },
      { label: "Correction", value: (p) => (p.correctionId ? h("a", { href: `#/audit?entityType=correction&entityId=${p.correctionId}` }, `#${p.correctionId}`) : "–") }
    ], [...rows].reverse(), { empty: "No punches in the last 30 days." })
  ]);
}
