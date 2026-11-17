import {
  api, card, currentQuery, downloadCsv, errorBox, fmtDateTime, fmtMinutes, fmtTime, h, load, mount, pageHead,
  replaceQuery, stat, table, tabs, todayISO
} from "../core.js";

const typeLabel = (t) => ({ I: "In", O: "Out", 0: "In", 1: "Out" }[t] || t || "–");

const VIEWS = {
  summary: {
    label: "Daily summary",
    url: "/api/attendance/summary",
    columns: [
      { label: "Date", value: (r) => r.date, csv: (r) => r.date },
      { label: "Device ID", value: (r) => r.employeeId, csv: (r) => r.employeeId },
      { label: "Name", value: (r) => r.employeeName || "–", csv: (r) => r.employeeName },
      { label: "First in", value: (r) => fmtTime(r.firstIn), csv: (r) => fmtTime(r.firstIn) },
      { label: "Last out", value: (r) => fmtTime(r.lastOut), csv: (r) => (r.lastOut ? fmtTime(r.lastOut) : "") },
      { label: "Punches", value: (r) => r.punches, class: "num", csv: (r) => r.punches },
      { label: "Worked", value: (r) => fmtMinutes(r.workedMinutes), class: "num", csv: (r) => r.workedMinutes, csvLabel: "Worked minutes" }
    ]
  },
  punches: {
    label: "All punches",
    url: "/api/attendance",
    paged: true,
    columns: [
      { label: "Time", value: (r) => fmtDateTime(r.checkTime), csv: (r) => fmtDateTime(r.checkTime) },
      { label: "Device ID", value: (r) => r.employeeId, csv: (r) => r.employeeId },
      { label: "Name", value: (r) => r.employeeName || "–", csv: (r) => r.employeeName },
      { label: "Type", value: (r) => typeLabel(r.checkType), csv: (r) => typeLabel(r.checkType) },
      { label: "Device", value: (r) => r.deviceId ?? "–", class: "num", csv: (r) => r.deviceId }
    ]
  }
};
const PAGE_SIZE = 100;

export const page = {
  title: "Punches",
  roles: ["ADMIN", "HR"],
  async render(container) {
    const q = currentQuery();
    const state = {
      view: VIEWS[q.get("view")] ? q.get("view") : "summary",
      from: q.get("from") || todayISO(),
      to: q.get("to") || q.get("from") || todayISO(),
      emp: q.get("employeeId") || "",
      page: 0,
      rows: []
    };
    const stats = h("div", { class: "stats" });
    const body = h("div");
    const from = h("input", { type: "date", value: state.from });
    const to = h("input", { type: "date", value: state.to });
    const emp = h("input", { placeholder: "All", size: "12", value: state.emp });
    const csv = h("button", { class: "btn", type: "button", onclick: exportCsv }, "Export CSV");
    const filterForm = h("form", { class: "filters" },
      h("label", { class: "field" }, "From", from),
      h("label", { class: "field" }, "To", to),
      h("label", { class: "field" }, "Device ID", emp),
      h("button", { class: "btn primary", type: "submit" }, "Apply"), csv);
    filterForm.addEventListener("submit", (e) => {
      e.preventDefault();
      Object.assign(state, { from: from.value, to: to.value || from.value, emp: emp.value.trim(), page: 0 });
      refresh();
    });
    const tabBar = h("div");

    mount(container,
      pageHead("Punches"),
      h("p", { class: "muted", style: "margin-top:-8px" },
        "Raw punches as received from the ZKTeco devices. Device IDs are linked to employees on the Employees page."),
      stats,
      card(null, [tabBar, filterForm, body]));

    loadStats();
    refresh();

    async function loadStats() {
      try {
        const s = await api.get("/api/sync/status");
        const ok = s.lastRun?.status === "SUCCESS";
        mount(stats,
          stat("Total punches", s.totalRecords.toLocaleString(), "stored in database"),
          stat("Latest punch", s.latestPunchAt ? fmtDateTime(s.latestPunchAt) : "None yet", "most recent punch"),
          stat("Last sync", s.lastRun ? s.lastRun.status : "Never",
               s.lastRun ? fmtDateTime(s.lastRun.finishedAt || s.lastRun.startedAt) : "waiting for the sync agent"));
        stats.lastElementChild.querySelector(".value").style.color = s.lastRun ? (ok ? "var(--ok)" : "var(--bad)") : "";
      } catch (e) {
        mount(stats, errorBox(e.message));
      }
    }

    async function refresh() {
      replaceQuery({ view: state.view, from: state.from, to: state.to, employeeId: state.emp });
      mount(tabBar, tabs(Object.entries(VIEWS).map(([k, v]) => [k, v.label]), state.view,
        (k) => { state.view = k; state.page = 0; refresh(); }));
      const view = VIEWS[state.view];
      await load(body, () => api.get(view.url, {
        from: state.from, to: state.to, employeeId: state.emp,
        ...(view.paged ? { page: state.page, size: PAGE_SIZE } : {})
      }), (rows) => {
        state.rows = rows;
        csv.disabled = rows.length === 0;
        const pager = view.paged && (state.page > 0 || rows.length === PAGE_SIZE)
          ? h("div", { class: "pager" }, `Page ${state.page + 1}`,
              h("button", { class: "btn small", disabled: state.page === 0,
                            onclick: () => { state.page--; refresh(); } }, "Previous"),
              h("button", { class: "btn small", disabled: rows.length < PAGE_SIZE,
                            onclick: () => { state.page++; refresh(); } }, "Next"))
          : null;
        return [table(view.columns, rows, {
          empty: "No punches in this period. Data arrives once the sync agent runs on the PC with the ZKTeco software."
        }), pager];
      });
    }

    function exportCsv() {
      const view = VIEWS[state.view];
      downloadCsv(`punches-${state.view}-${state.from}_to_${state.to}.csv`,
        view.columns.map((c) => c.csvLabel || c.label),
        state.rows.map((r) => view.columns.map((c) => c.csv(r))));
    }
  }
};
