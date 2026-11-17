import {
  api, card, confirmDialog, currentQuery, fmtDate, fmtDateTime, fmtNumber, formModal, h, hasRole, load, mount,
  pageHead, replaceQuery, session, table, tabs, toast
} from "../core.js";
import { balanceTable, lookup, requestPill } from "../ui.js";

/** Leave application. With `employee`, HR records leave for that person. */
export async function applyDialog({ employee, onSaved } = {}) {
  const types = (await lookup("leaveTypes")).filter((t) => t.active);
  const preview = h("div", { class: "small muted" });
  const m = formModal({
    title: employee ? `Record leave for ${employee.fullName}` : "Apply for leave",
    intro: employee ? "Recorded leave still needs approval by the employee's manager or HR." : null,
    fields: [
      { name: "leaveTypeId", label: "Leave type", type: "select", required: true, placeholder: "Choose…",
        options: types.map((t) => ({ value: t.id, label: `${t.name}${t.paid ? "" : " (unpaid)"}` })) },
      { name: "startDate", label: "From", type: "date", required: true },
      { name: "endDate", label: "To", type: "date", required: true },
      { name: "reason", label: "Reason", type: "textarea", wide: true }
    ],
    submitLabel: employee ? "Record leave" : "Apply",
    onSubmit: async (v) => {
      await api.post("/api/leave/requests", { ...v, leaveTypeId: Number(v.leaveTypeId), employeeId: employee?.id });
      toast(employee ? "Leave recorded." : "Leave request sent for approval.");
      onSaved?.();
    }
  });
  const modalBody = m.overlay.querySelector(".form-grid");
  modalBody.append(h("div", { class: "wide" }, preview));
  const start = modalBody.querySelector('[name="startDate"]');
  const end = modalBody.querySelector('[name="endDate"]');
  const update = async () => {
    if (start.value && !end.value) end.value = start.value;
    if (!start.value || !end.value) { preview.textContent = ""; return; }
    try {
      const days = await api.get("/api/leave/working-days", { from: start.value, to: end.value, employeeId: employee?.id });
      preview.textContent = `${fmtNumber(days, days % 1 ? 1 : 0)} working day(s), not counting weekly offs and holidays.`;
    } catch { preview.textContent = ""; }
  };
  start.addEventListener("change", update);
  end.addEventListener("change", update);
}

function decisionDialog(r, approve, onDone) {
  formModal({
    title: `${approve ? "Approve" : "Reject"} leave for ${r.employeeName}`,
    intro: `${r.leaveTypeName}, ${fmtDate(r.startDate)} to ${fmtDate(r.endDate)} (${fmtNumber(r.days, 1)} days).`,
    fields: [{ name: "note", label: approve ? "Note (optional)" : "Reason for rejecting", type: "textarea", wide: true, required: !approve }],
    submitLabel: approve ? "Approve" : "Reject",
    onSubmit: async (v) => {
      await api.post(`/api/leave/requests/${r.id}/${approve ? "approve" : "reject"}`, v);
      toast(approve ? "Leave approved." : "Leave rejected.");
      onDone();
    }
  });
}

export const page = {
  title: "Leave",
  async render(container) {
    const q = currentQuery();
    const lead = hasRole("ADMIN", "HR", "MANAGER");
    const available = [];
    if (session.employee) available.push(["mine", "My requests"]);
    if (lead) available.push(["approvals", "Approvals"], ["all", hasRole("ADMIN", "HR") ? "All requests" : "My team"]);
    if (session.employee) available.push(["balance", "My balance"]);
    let tab = available.some(([k]) => k === q.get("tab")) ? q.get("tab") : available[0]?.[0];
    const tabBar = h("div");
    const body = h("div");
    mount(container,
      pageHead("Leave", session.employee ? h("button", { class: "btn primary", type: "button", onclick: () => applyDialog({ onSaved: show }) }, "Apply for leave") : null),
      available.length ? card(null, [tabBar, body]) : h("div", { class: "alert info" }, "Your account is not linked to an employee profile, so you cannot apply for leave."));
    if (!available.length) return;
    if (q.get("apply") && session.employee) { replaceQuery({}); applyDialog({ onSaved: show }); }
    show();

    function show() {
      replaceQuery({ tab });
      mount(tabBar, tabs(available, tab, (k) => { tab = k; show(); }));
      if (tab === "balance") {
        return load(body, () => api.get("/api/leave/balances", { employeeId: session.employee.id }),
          (b) => [table(balanceTable(b), b), h("p", { class: "muted small", style: "padding:0 16px" }, "Allowances are per calendar year. Pending requests are already deducted from what is left.")]);
      }
      const scope = tab;
      load(body, () => api.get("/api/leave/requests", { scope }), (rows) => table([
        scope !== "mine" ? { label: "Employee", value: (r) => h("a", { href: `#/employees/${r.employeeId}` }, r.employeeName) } : null,
        { label: "Type", value: (r) => r.leaveTypeName + (r.paid ? "" : " (unpaid)") },
        { label: "From", value: (r) => fmtDate(r.startDate) },
        { label: "To", value: (r) => fmtDate(r.endDate) },
        { label: "Days", value: (r) => fmtNumber(r.days, r.days % 1 ? 1 : 0), class: "num" },
        { label: "Reason", value: (r) => r.reason || "–", class: "wrap" },
        { label: "Status", value: (r) => requestPill(r.status) },
        { label: "Decision", value: (r) => (r.decidedByName ? h("span", { class: "small" }, `${r.decidedByName}, ${fmtDateTime(r.decidedAt)}`, r.decisionNote ? h("br") : null, r.decisionNote ? h("span", { class: "muted" }, r.decisionNote) : null) : "–"), class: "wrap" },
        { label: "", value: (r) => h("span", { style: "display:flex;gap:6px" },
            r.canDecide ? h("button", { class: "btn small primary", type: "button", onclick: () => decisionDialog(r, true, show) }, "Approve") : null,
            r.canDecide ? h("button", { class: "btn small", type: "button", onclick: () => decisionDialog(r, false, show) }, "Reject") : null,
            r.canCancel ? h("button", { class: "btn small danger", type: "button", onclick: () => cancel(r) }, "Cancel") : null) }
      ].filter(Boolean), rows, { empty: scope === "approvals" ? "Nothing waiting for approval." : "No leave requests yet." }));
    }

    async function cancel(r) {
      if (!(await confirmDialog(`Cancel ${r.leaveTypeName} from ${fmtDate(r.startDate)} to ${fmtDate(r.endDate)}?`, { confirmLabel: "Cancel leave", danger: true }))) return;
      try { await api.post(`/api/leave/requests/${r.id}/cancel`); toast("Leave cancelled."); show(); }
      catch (e) { toast(e.message, "error"); }
    }
  }
};
