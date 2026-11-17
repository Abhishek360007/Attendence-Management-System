import { api, card, fmtDateTime, h, hasRole, load, mount, pageHead, pill, stat, table, toast } from "../core.js";

export const page = {
  title: "Device sync",
  roles: ["ADMIN", "HR"],
  async render(container) {
    const stats = h("div", { class: "stats" });
    const history = h("div");
    const syncNow = hasRole("ADMIN")
      ? h("button", { class: "btn", type: "button", onclick: run }, "Sync now")
      : null;
    mount(container,
      pageHead("Device sync", syncNow),
      h("p", { class: "muted", style: "margin-top:-8px" },
        "Punches are copied from the ZKTeco database by the sync agent on the office PC. ",
        "Runs that copied punches or failed are listed below."),
      stats,
      card("Recent runs", history));
    refresh();

    async function refresh() {
      const s = await api.get("/api/sync/status").catch(() => null);
      if (s) {
        mount(stats,
          stat("Total punches", s.totalRecords.toLocaleString(), "stored", { icon: "fingerprint", tone: "blue" }),
          stat("Latest punch", s.latestPunchAt ? fmtDateTime(s.latestPunchAt) : "None yet", "from the devices", { icon: "clock", tone: "violet" }),
          stat("Last run", s.lastRun ? fmtDateTime(s.lastRun.startedAt) : "Never", s.lastRun?.status, { icon: "sync", tone: s.lastRun?.status === "FAILED" ? "red" : "green" }));
      }
      load(history, () => api.get("/api/sync/history", { limit: 50 }), (runs) => table([
        { label: "Started", value: (r) => fmtDateTime(r.startedAt) },
        { label: "Status", value: (r) => pill(r.status, r.status === "SUCCESS" ? "ok" : "bad") },
        { label: "New punches", value: (r) => r.recordsSynced, class: "num" },
        { label: "Message", value: (r) => h("span", { class: "muted" }, r.message || ""), class: "wrap" }
      ], runs, { empty: "No sync runs yet. They appear once the sync agent on the ZKTeco PC copies punches." }));
    }

    async function run() {
      try {
        const r = await api.post("/api/sync");
        toast(r.status === "SUCCESS" ? `Synced ${r.recordsSynced} new punch(es).` : r.message, r.status === "SUCCESS" ? "info" : "error");
        refresh();
      } catch (e) {
        toast(e.message, "error");
      }
    }
  }
};
