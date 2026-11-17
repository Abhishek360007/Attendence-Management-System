import {
  api, card, confirmDialog, currentQuery, fmtDateTime, formModal, h, hasRole, load, mount, pageHead, replaceQuery,
  person, session, table, tabs, titleCase, toast
} from "../core.js";
import { companyToday, requestPill } from "../ui.js";

const ACTIONS = [
  { value: "ADD", label: "Add a missing punch" },
  { value: "MODIFY", label: "Change a wrong punch time" },
  { value: "REMOVE", label: "Remove a duplicate punch" }
];

/**
 * Correction request. Pick a date, then the punch to change or remove (or a time to add).
 * With `employee` set, a manager or HR files it for that person and may approve it at once.
 */
export async function correctionDialog({ employee, onSaved } = {}) {
  const target = employee || session.employee;
  if (!target) { toast("Your account is not linked to an employee profile.", "error"); return; }
  const forOther = !!employee && employee.id !== session.employee?.id;
  const punchSelect = h("select", { name: "attendanceRecordId" });
  const punchField = h("label", { class: "field wide" }, "Punch", punchSelect);
  const m = formModal({
    title: forOther ? `Correct a punch for ${target.fullName}` : "Fix a punch",
    intro: "Punches are never edited directly: your request is checked by your manager or HR, and the original punch is kept in the record.",
    fields: [
      { name: "action", label: "What is wrong?", type: "select", options: ACTIONS, required: true, wide: true },
      { name: "date", label: "Date", type: "date", required: true },
      { name: "time", label: "Correct time", type: "time" },
      { name: "reason", label: "Reason", type: "textarea", required: true, wide: true,
        placeholder: "e.g. forgot to punch out; card reader was not working" },
      ...(forOther && hasRole("ADMIN", "HR", "MANAGER") ? [{ name: "approveNow", label: "Approve immediately (you are the approver)", type: "checkbox", wide: true }] : [])
    ],
    values: { action: "ADD", date: companyToday() },
    submitLabel: "Send request",
    onSubmit: async (v) => {
      const body = { employeeId: forOther ? target.id : null, action: v.action, reason: v.reason, approveNow: !!v.approveNow };
      if (v.action !== "ADD") {
        if (!punchSelect.value) throw new Error("Choose the punch to " + (v.action === "MODIFY" ? "change." : "remove."));
        body.attendanceRecordId = Number(punchSelect.value);
      }
      if (v.action !== "REMOVE") {
        if (!v.time) throw new Error("Enter the correct time.");
        body.requestedTime = `${v.date}T${v.time}`;
      }
      const saved = await api.post("/api/corrections", body);
      toast(saved.status === "APPROVED" ? "Correction applied." : "Correction sent for approval.");
      onSaved?.();
    }
  });
  const grid = m.overlay.querySelector(".form-grid");
  const action = grid.querySelector('[name="action"]');
  const date = grid.querySelector('[name="date"]');
  const time = grid.querySelector('[name="time"]').closest("label");
  grid.insertBefore(punchField, time);
  const refresh = async () => {
    const needsPunch = action.value !== "ADD";
    punchField.classList.toggle("hidden", !needsPunch);
    time.classList.toggle("hidden", action.value === "REMOVE");
    if (!needsPunch || !date.value) return;
    mount(punchSelect, h("option", { value: "" }, "Loading…"));
    try {
      const punches = (await api.get(`/api/employees/${target.id}/punches`, { from: date.value, to: date.value })).filter((p) => !p.voided);
      mount(punchSelect, punches.length
        ? [h("option", { value: "" }, "Choose…"), punches.map((p) => h("option", { value: p.id }, `${fmtDateTime(p.checkTime).slice(11)}${p.source === "MANUAL" ? " (corrected)" : ""}`))]
        : h("option", { value: "" }, "No punches on this date"));
    } catch (e) {
      mount(punchSelect, h("option", { value: "" }, e.message));
    }
  };
  action.addEventListener("change", refresh);
  date.addEventListener("change", refresh);
  refresh();
}

function describe(c) {
  switch (c.action) {
    case "ADD": return ["Add ", h("strong", null, fmtDateTime(c.requestedTime))];
    case "MODIFY": return ["Change ", h("s", null, fmtDateTime(c.originalTime)), " to ", h("strong", null, fmtDateTime(c.requestedTime))];
    default: return ["Remove ", h("s", null, fmtDateTime(c.originalTime))];
  }
}

function decisionDialog(c, approve, onDone) {
  formModal({
    title: `${approve ? "Approve" : "Reject"} correction for ${c.employeeName}`,
    intro: `${titleCase(c.action)}: ${c.action === "ADD" ? fmtDateTime(c.requestedTime) : fmtDateTime(c.originalTime)}${c.action === "MODIFY" ? " → " + fmtDateTime(c.requestedTime) : ""}. Reason: ${c.reason}`,
    fields: [{ name: "note", label: approve ? "Note (optional)" : "Reason for rejecting", type: "textarea", wide: true, required: !approve }],
    submitLabel: approve ? "Approve" : "Reject",
    onSubmit: async (v) => {
      await api.post(`/api/corrections/${c.id}/${approve ? "approve" : "reject"}`, v);
      toast(approve ? "Correction applied." : "Correction rejected.");
      onDone();
    }
  });
}

export const page = {
  title: "Punch corrections",
  async render(container) {
    const q = currentQuery();
    const lead = hasRole("ADMIN", "HR", "MANAGER");
    const available = [];
    if (session.employee) available.push(["mine", "My requests"]);
    if (lead) available.push(["approvals", "Approvals"], ["all", hasRole("ADMIN", "HR") ? "All corrections" : "My team"]);
    let tab = available.some(([k]) => k === q.get("tab")) ? q.get("tab") : available[0]?.[0];
    const tabBar = h("div");
    const body = h("div");
    mount(container,
      pageHead("Punch corrections", session.employee ? h("button", { class: "btn primary", type: "button", onclick: () => correctionDialog({ onSaved: show }) }, "Fix a punch") : null),
      h("p", { class: "muted", style: "margin-top:-8px" }, "Forgot to punch, or the device recorded the wrong time? Request a correction. Every change is approved by someone else and kept in the audit log."),
      available.length ? card(null, [tabBar, body]) : h("div", { class: "alert info" }, "Your account is not linked to an employee profile."));
    if (!available.length) return;
    if (q.get("new") && session.employee) { replaceQuery({}); correctionDialog({ onSaved: show }); }
    show();

    function show() {
      replaceQuery({ tab });
      mount(tabBar, tabs(available, tab, (k) => { tab = k; show(); }));
      load(body, () => api.get("/api/corrections", { scope: tab }), (rows) => table([
        tab !== "mine" ? { label: "Employee", value: (c) => person(c.employeeName, c.employeeCode, `#/employees/${c.employeeId}`) } : null,
        { label: "Change", value: (c) => describe(c) },
        { label: "Reason", value: (c) => c.reason, class: "wrap" },
        { label: "Requested", value: (c) => h("span", { class: "small" }, `${c.requestedByName || ""}, ${fmtDateTime(c.createdAt)}`) },
        { label: "Status", value: (c) => requestPill(c.status) },
        { label: "Decision", value: (c) => (c.decidedByName && c.status !== "CANCELLED" ? h("span", { class: "small" }, c.decidedByName, c.decisionNote ? [h("br"), h("span", { class: "muted" }, c.decisionNote)] : null) : "–"), class: "wrap" },
        { label: "", value: (c) => h("span", { style: "display:flex;gap:6px" },
            c.canDecide ? h("button", { class: "btn small primary", type: "button", onclick: () => decisionDialog(c, true, show) }, "Approve") : null,
            c.canDecide ? h("button", { class: "btn small", type: "button", onclick: () => decisionDialog(c, false, show) }, "Reject") : null,
            c.canCancel ? h("button", { class: "btn small danger", type: "button", onclick: () => cancel(c) }, "Cancel") : null) }
      ].filter(Boolean), rows, { empty: tab === "approvals" ? "Nothing waiting for approval." : "No correction requests." }));
    }

    async function cancel(c) {
      if (!(await confirmDialog("Cancel this correction request?", { confirmLabel: "Cancel request", danger: true }))) return;
      try { await api.post(`/api/corrections/${c.id}/cancel`); toast("Request cancelled."); show(); }
      catch (e) { toast(e.message, "error"); }
    }
  }
};
