// Attendance-specific widgets shared by several pages.
import { api, fmtDate, fmtDay, fmtHours, fmtMinutes, fmtNumber, fmtPercent, fmtTime, h, pill, session, stat, titleCase } from "./core.js";

export const STATUS = {
  PRESENT: ["Present", "ok"],
  HALF_DAY: ["Half day", "warn"],
  ABSENT: ["Absent", "bad"],
  LEAVE: ["Leave", "info"],
  HOLIDAY: ["Holiday", "neutral"],
  WEEKLY_OFF: ["Weekly off", "neutral"],
  UPCOMING: ["Not yet", "neutral"]
};

export function statusPill(status) {
  const [label, kind] = STATUS[status] || [titleCase(status), "neutral"];
  return pill(label, kind);
}

export const REQUEST_STATUS = { PENDING: "warn", APPROVED: "ok", REJECTED: "bad", CANCELLED: "neutral" };
export const requestPill = (s) => pill(titleCase(s), REQUEST_STATUS[s] || "neutral");

/** Flags that make a day worth a second look, as small pills. */
export function dayFlags(d) {
  const out = [];
  if (d.lateMinutes > 0) out.push(pill(`Late ${d.lateMinutes}m`, "warn"));
  if (d.earlyMinutes > 0) out.push(pill(`Left ${d.earlyMinutes}m early`, "warn"));
  if (d.overtimeMinutes > 0) out.push(pill(`OT ${fmtMinutes(d.overtimeMinutes)}`, "info"));
  if (d.missingOut) out.push(pill("Missing punch", "bad"));
  return out.length ? h("span", { style: "display:inline-flex;gap:4px;flex-wrap:wrap" }, out) : null;
}

export function dayNote(d) {
  if (d.status === "LEAVE") return `${d.note}${d.paidLeave === false ? " (unpaid)" : ""}`;
  if (d.status === "HOLIDAY") return d.note;
  return null;
}

/** Stat tiles for a PeriodSummary. */
export function summaryStats(s) {
  return h("div", { class: "stats" },
    stat("Attendance", fmtPercent(s.attendancePercent), `${fmtNumber(s.presentDays, s.presentDays % 1 ? 1 : 0)} of ${s.workingDays - s.leaveDays} expected days`, { icon: "check", tone: "green" }),
    stat("Absent", fmtNumber(s.absentDays, s.absentDays % 1 ? 1 : 0), s.halfDays ? `${s.halfDays} half day(s)` : "days", { icon: "xCircle", tone: "red" }),
    stat("Late arrivals", s.lateDays, s.lateMinutes ? `${fmtMinutes(s.lateMinutes)} in total` : "always on time", { icon: "clock", tone: "amber" }),
    stat("Leave", s.leaveDays, s.unpaidLeaveDays ? `${s.unpaidLeaveDays} unpaid` : "days", { icon: "plane", tone: "blue" }),
    stat("Overtime", `${fmtHours(s.overtimeMinutes)} h`, s.earlyDays ? `${s.earlyDays} early departure(s)` : "extra hours", { icon: "hourglass", tone: "violet" }));
}

/** Month calendar of DayRecords (days outside employment are blank). */
export function calendar(month, days) {
  const [y, m] = month.split("-").map(Number);
  const first = new Date(y, m - 1, 1);
  const count = new Date(y, m, 0).getDate();
  const byDate = Object.fromEntries(days.map((d) => [d.date, d]));
  const offset = (first.getDay() + 6) % 7; // Monday first
  const cells = [];
  for (let i = 0; i < offset; i++) cells.push(h("div", { class: "day blank" }));
  for (let day = 1; day <= count; day++) {
    const iso = `${y}-${String(m).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
    const d = byDate[iso];
    if (!d) { cells.push(h("div", { class: "day", style: "opacity:.4" }, h("div", { class: "d" }, day))); continue; }
    const [label] = STATUS[d.status] || [d.status];
    const time = d.firstIn ? `${fmtTime(d.firstIn)}–${d.lastOut ? fmtTime(d.lastOut) : "?"}` : "";
    const title = [fmtDay(iso), label, dayNote(d), time,
      d.lateMinutes ? `late ${d.lateMinutes} min` : "", d.overtimeMinutes ? `overtime ${fmtMinutes(d.overtimeMinutes)}` : ""]
      .filter(Boolean).join(" · ");
    cells.push(h("div", { class: `day s-${d.status}`, title },
      h("div", { class: "d" }, day),
      h("div", null, d.status === "PRESENT" || d.status === "HALF_DAY" ? time : label),
      d.lateMinutes ? h("div", { style: "color:var(--warn)" }, `late ${d.lateMinutes}m`) : null,
      d.missingOut ? h("div", { style: "color:var(--bad)" }, "missing punch") : null));
  }
  return h("div", null,
    h("div", { class: "calendar" }, ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((d) => h("div", { class: "dow" }, d)), cells),
    h("div", { class: "legend", style: "padding:10px 0 0" },
      Object.entries({ PRESENT: "--ok-bg", ABSENT: "--bad-bg", LEAVE: "--info-bg", HOLIDAY: "--surface-2" }).map(([k, c]) =>
        h("span", null, h("i", { style: `background:var(${c});border:1px solid var(--border)` }), k === "HOLIDAY" ? "Holiday / weekly off" : STATUS[k][0]))));
}

/** Day-by-day table for one employee. */
export function dayTable(days) {
  return [
    { label: "Date", value: (d) => fmtDay(d.date) },
    { label: "Status", value: (d) => statusPill(d.status) },
    { label: "In", value: (d) => fmtTime(d.firstIn) },
    { label: "Out", value: (d) => fmtTime(d.lastOut) },
    { label: "Worked", value: (d) => fmtMinutes(d.workedMinutes), class: "num" },
    { label: "Notes", value: (d) => [dayNote(d), dayFlags(d)].filter(Boolean) }
  ];
}

/** Leave balances table. */
export function balanceTable(balances) {
  return [
    { label: "Leave type", value: (b) => [b.leaveType, b.paid ? null : h("span", { class: "muted small" }, " (unpaid)")] },
    { label: "Allowance", value: (b) => (b.entitled === null ? "No limit" : fmtNumber(b.entitled, 1)), class: "num" },
    { label: "Taken", value: (b) => fmtNumber(b.used, 1), class: "num" },
    { label: "Pending", value: (b) => fmtNumber(b.pending, 1), class: "num" },
    { label: "Left", value: (b) => (b.remaining === null ? "–" : h("strong", null, fmtNumber(b.remaining, 1))), class: "num" }
  ];
}

let cache = {};
/** Cached lookups for form dropdowns; call invalidate() after changing them. */
export async function lookup(name) {
  const urls = { departments: "/api/departments", schedules: "/api/schedules", leaveTypes: "/api/leave/types",
                 employees: "/api/employees?active=true" };
  if (!cache[name]) cache[name] = api.get(urls[name]).catch((e) => { delete cache[name]; throw e; });
  return cache[name];
}
export function invalidate(...names) {
  if (!names.length) cache = {};
  names.forEach((n) => delete cache[n]);
}

export async function departmentFilter(selected, onChange) {
  const depts = await lookup("departments").catch(() => []);
  const sel = h("select", { onchange: () => onChange(sel.value) },
    h("option", { value: "" }, "All departments"),
    depts.map((d) => h("option", { value: d.id, selected: String(d.id) === String(selected || "") }, d.name)));
  return h("label", { class: "field" }, "Department", sel);
}

export function monthInput(value, onChange) {
  const input = h("input", { type: "month", value, onchange: () => input.value && onChange(input.value) });
  return h("label", { class: "field" }, "Month", input);
}

export const shiftMonth = (ym, delta) => {
  const [y, m] = ym.split("-").map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
};

export function companyToday() {
  return session.settings?.today || new Date().toISOString().slice(0, 10);
}

export { fmtDate };
