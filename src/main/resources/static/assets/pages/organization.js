import {
  api, card, confirmDialog, currentQuery, fmtDate, formModal, h, load, mount, pageHead, pill, replaceQuery, table,
  tabs, titleCase, toast
} from "../core.js";
import { invalidate, lookup } from "../ui.js";

const DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

export const page = {
  title: "Departments & schedules",
  roles: ["ADMIN", "HR"],
  async render(container) {
    let tab = currentQuery().get("tab") || "departments";
    let year = Number(currentQuery().get("year")) || new Date().getFullYear();
    const tabBar = h("div");
    const actions = h("div", { class: "actions" });
    const body = h("div");
    mount(container, h("div", { class: "page-head" }, h("h1", null, "Departments & schedules"), actions), card(null, [tabBar, body]));
    show();

    function show() {
      replaceQuery({ tab, year: tab === "holidays" ? year : "" });
      mount(tabBar, tabs([["departments", "Departments"], ["schedules", "Work schedules"], ["holidays", "Holidays"], ["leave", "Leave types"]], tab,
        (k) => { tab = k; show(); }));
      ({ departments, schedules, holidays, leave })[tab]();
    }

    // ---------------------------------------------------------------- departments
    function departments() {
      mount(actions, h("button", { class: "btn primary", type: "button", onclick: () => editDepartment() }, "Add department"));
      load(body, () => api.get("/api/departments"), (rows) => table([
        { label: "Department", value: (d) => h("strong", null, d.name) },
        { label: "Head", value: (d) => (d.managerName ? h("a", { href: `#/employees/${d.managerEmployeeId}` }, d.managerName) : "–") },
        { label: "Schedule", value: (d) => d.workScheduleName || h("span", { class: "muted" }, "Default") },
        { label: "Employees", value: (d) => h("a", { href: `#/employees?departmentId=${d.id}` }, d.employeeCount), class: "num" },
        { label: "", value: (d) => h("span", { style: "display:flex;gap:6px" },
            h("button", { class: "btn small", type: "button", onclick: () => editDepartment(d) }, "Edit"),
            h("button", { class: "btn small danger", type: "button", onclick: () => remove(`/api/departments/${d.id}`, `Delete the ${d.name} department?`, ["departments"]) }, "Delete")) }
      ], rows, { empty: "No departments yet. Departments group employees, set their schedule and who approves their requests." }));
    }

    async function editDepartment(d) {
      const [schedulesList, employees] = await Promise.all([lookup("schedules"), lookup("employees")]);
      formModal({
        title: d ? `Edit ${d.name}` : "Add department",
        intro: "The department head approves leave and corrections for everyone in it (unless an employee has a direct manager).",
        fields: [
          { name: "name", label: "Name", required: true, wide: true },
          { name: "workScheduleId", label: "Work schedule", type: "select", placeholder: "Default schedule",
            options: schedulesList.map((s) => ({ value: s.id, label: s.name })) },
          { name: "managerEmployeeId", label: "Department head", type: "select", placeholder: "None",
            options: employees.map((e) => ({ value: e.id, label: `${e.fullName} (${e.employeeCode})` })) }
        ],
        values: d || {},
        onSubmit: async (v) => {
          const body = { name: v.name, workScheduleId: v.workScheduleId ? Number(v.workScheduleId) : null,
                         managerEmployeeId: v.managerEmployeeId ? Number(v.managerEmployeeId) : null };
          d ? await api.put(`/api/departments/${d.id}`, body) : await api.post("/api/departments", body);
          invalidate("departments");
          toast("Department saved.");
          show();
        }
      });
    }

    // ---------------------------------------------------------------- schedules
    function schedules() {
      mount(actions, h("button", { class: "btn primary", type: "button", onclick: () => editSchedule() }, "Add schedule"));
      load(body, () => api.get("/api/schedules"), (rows) => table([
        { label: "Schedule", value: (s) => [h("strong", null, s.name), s.isDefault ? [" ", pill("Default", "info")] : null] },
        { label: "Hours", value: (s) => `${s.startTime.slice(0, 5)}–${s.endTime.slice(0, 5)}${s.endTime <= s.startTime ? " (next day)" : ""}` },
        { label: "Late after", value: (s) => `${s.lateGraceMinutes} min`, class: "num" },
        { label: "Early before", value: (s) => `${s.earlyGraceMinutes} min`, class: "num" },
        { label: "Overtime from", value: (s) => `${s.overtimeMinMinutes} min`, class: "num" },
        { label: "Half day under", value: (s) => (s.halfDayMinutes ? `${(s.halfDayMinutes / 60).toFixed(1)} h` : "off"), class: "num" },
        { label: "Weekly off", value: (s) => s.weeklyOffs.split(",").filter(Boolean).map((d) => titleCase(d).slice(0, 3)).join(", ") || "none" },
        { label: "", value: (s) => h("span", { style: "display:flex;gap:6px" },
            h("button", { class: "btn small", type: "button", onclick: () => editSchedule(s) }, "Edit"),
            s.isDefault ? null : h("button", { class: "btn small danger", type: "button", onclick: () => remove(`/api/schedules/${s.id}`, `Delete the ${s.name} schedule?`, ["schedules"]) }, "Delete")) }
      ], rows));
    }

    function editSchedule(s) {
      const offs = new Set((s?.weeklyOffs || "SUNDAY").split(",").filter(Boolean));
      formModal({
        title: s ? `Edit ${s.name}` : "Add schedule",
        intro: "Changes apply to all past and future days of employees on this schedule (attendance is recalculated, never stored).",
        fields: [
          { name: "name", label: "Name", required: true, wide: true },
          { name: "startTime", label: "Start", type: "time", required: true },
          { name: "endTime", label: "End", type: "time", required: true, help: "Earlier than start = night shift ending next day." },
          { name: "lateGraceMinutes", label: "Grace before late (min)", type: "number", min: "0", max: "240", required: true },
          { name: "earlyGraceMinutes", label: "Grace before early departure (min)", type: "number", min: "0", max: "240", required: true },
          { name: "overtimeMinMinutes", label: "Count overtime from (min)", type: "number", min: "0", max: "600", required: true },
          { name: "halfDayMinutes", label: "Half day if worked under (min)", type: "number", min: "0", max: "1440", required: true, help: "0 turns half days off." },
          ...DAYS.map((d) => ({ name: "off_" + d, label: `${titleCase(d)} off`, type: "checkbox" })),
          { name: "isDefault", label: "Default schedule (for employees and departments without one)", type: "checkbox", wide: true }
        ],
        values: {
          lateGraceMinutes: 10, earlyGraceMinutes: 10, overtimeMinMinutes: 30, halfDayMinutes: 240, ...s,
          startTime: s?.startTime?.slice(0, 5) || "09:00", endTime: s?.endTime?.slice(0, 5) || "18:00",
          ...Object.fromEntries(DAYS.map((d) => ["off_" + d, offs.has(d)]))
        },
        onSubmit: async (v) => {
          const body = {
            name: v.name, startTime: v.startTime, endTime: v.endTime, lateGraceMinutes: v.lateGraceMinutes,
            earlyGraceMinutes: v.earlyGraceMinutes, overtimeMinMinutes: v.overtimeMinMinutes, halfDayMinutes: v.halfDayMinutes,
            weeklyOffs: DAYS.filter((d) => v["off_" + d]), isDefault: v.isDefault
          };
          s ? await api.put(`/api/schedules/${s.id}`, body) : await api.post("/api/schedules", body);
          invalidate("schedules");
          toast("Schedule saved.");
          show();
        }
      });
    }

    // ---------------------------------------------------------------- holidays
    function holidays() {
      const yearInput = h("input", { type: "number", value: year, min: "2000", max: "2100", style: "width:100px",
        onchange: () => { year = Number(yearInput.value) || year; show(); } });
      mount(actions,
        h("label", { class: "field" }, "Year", yearInput),
        h("button", { class: "btn primary", type: "button", style: "align-self:end", onclick: addHoliday }, "Add holiday"));
      load(body, () => api.get("/api/holidays", { year }), (rows) => table([
        { label: "Date", value: (d) => fmtDate(d.holidayDate) },
        { label: "Day", value: (d) => new Date(d.holidayDate + "T12:00").toLocaleDateString(undefined, { weekday: "long" }) },
        { label: "Holiday", value: (d) => h("strong", null, d.name) },
        { label: "", value: (d) => h("button", { class: "btn small danger", type: "button", onclick: () => remove(`/api/holidays/${d.id}`, `Delete ${d.name}?`, []) }, "Delete") }
      ], rows, { empty: `No holidays for ${year}. Holidays are paid days off for everyone and never count as absence.` }));
    }

    function addHoliday() {
      formModal({
        title: "Add holiday",
        fields: [
          { name: "holidayDate", label: "Date", type: "date", required: true },
          { name: "name", label: "Name", required: true, placeholder: "e.g. Diwali" }
        ],
        onSubmit: async (v) => { await api.post("/api/holidays", v); toast("Holiday added."); year = Number(v.holidayDate.slice(0, 4)); show(); }
      });
    }

    // ---------------------------------------------------------------- leave types
    function leave() {
      mount(actions, h("button", { class: "btn primary", type: "button", onclick: () => editType() }, "Add leave type"));
      load(body, () => api.get("/api/leave/types"), (rows) => table([
        { label: "Leave type", value: (t) => h("strong", null, t.name) },
        { label: "Paid", value: (t) => (t.paid ? pill("Paid", "ok") : pill("Unpaid", "warn")) },
        { label: "Days per year", value: (t) => (t.annualQuota === null ? "No limit" : t.annualQuota), class: "num" },
        { label: "Status", value: (t) => (t.active ? pill("Offered", "ok") : pill("Retired", "neutral")) },
        { label: "", value: (t) => h("button", { class: "btn small", type: "button", onclick: () => editType(t) }, "Edit") }
      ], rows));
    }

    function editType(t) {
      formModal({
        title: t ? `Edit ${t.name}` : "Add leave type",
        intro: "Unpaid leave reduces payable days in payroll. Individual allowances can be changed per employee.",
        fields: [
          { name: "name", label: "Name", required: true, wide: true },
          { name: "annualQuota", label: "Days per year", type: "number", min: "0", step: "0.5", help: "Leave empty for no limit." },
          { name: "paid", label: "Paid leave", type: "checkbox" },
          ...(t ? [{ name: "active", label: "Still offered", type: "checkbox" }] : [])
        ],
        values: t || { paid: true, active: true },
        onSubmit: async (v) => {
          t ? await api.put(`/api/leave/types/${t.id}`, v) : await api.post("/api/leave/types", v);
          invalidate("leaveTypes");
          toast("Leave type saved.");
          show();
        }
      });
    }

    async function remove(url, message, caches) {
      if (!(await confirmDialog(message, { confirmLabel: "Delete", danger: true }))) return;
      try { await api.del(url); invalidate(...caches); toast("Deleted."); show(); }
      catch (e) { toast(e.message, "error"); }
    }
  }
};

export { pageHead };
