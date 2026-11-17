import { api, card, fmtDateTime, h, icon, load, mount, navigate, pageHead, toast } from "../core.js";

const KIND_ICONS = {
  LEAVE: "leave", CORRECTION: "wrench", DAILY_ABSENCE: "users", TEAM_ABSENCE: "users", LATE_ARRIVAL: "clock",
  IRREGULARITIES: "alert", REPORT: "report"
};
const kindIcon = (kind) => KIND_ICONS[Object.keys(KIND_ICONS).find((k) => kind.startsWith(k))] || "bell";

export const page = {
  title: "Notifications",
  async render(container) {
    const body = h("div");
    const readAll = h("button", { class: "btn", type: "button", onclick: markAll }, icon("check", 16), "Mark all as read");
    const emailNote = h("div");
    mount(container, pageHead("Notifications", readAll), emailNote, card(null, body));
    api.get("/api/notifications/unread").then((r) => {
      if (!r.emailEnabled) mount(emailNote, h("p", { class: "muted small", style: "margin:-12px 0 16px" }, "Email is not set up, so notifications appear only here."));
    }).catch(() => {});
    refresh();

    function refresh() {
      load(body, () => api.get("/api/notifications"), (rows) => rows.length
        ? h("div", null, rows.map((n) => h("div", {
            class: `notice${n.readAt ? "" : " unread"}`, style: n.link ? "cursor:pointer" : "", onclick: () => open(n)
          },
          h("div", { class: "dot" }, icon(kindIcon(n.kind), 18)),
          h("div", { style: "display:grid;gap:3px;min-width:0" },
            h("div", { style: "display:flex;justify-content:space-between;gap:12px" },
              h("strong", { style: "font-weight:600" }, n.title),
              h("span", { class: "muted small", style: "white-space:nowrap" }, fmtDateTime(n.createdAt))),
            n.body ? h("div", { class: "muted", style: "white-space:pre-line" }, n.body) : null))))
        : h("div", { class: "empty" }, icon("bell", 28),
            "No notifications yet. You'll be told here about leave and correction requests, decisions, absences and reports."));
    }

    async function open(n) {
      if (!n.readAt) {
        await api.post(`/api/notifications/${n.id}/read`).catch(() => {});
        window.dispatchEvent(new Event("unread-changed"));
      }
      if (n.link) navigate(n.link.split("?")[0], Object.fromEntries(new URLSearchParams(n.link.split("?")[1] || "")));
      else refresh();
    }

    async function markAll() {
      try {
        await api.post("/api/notifications/read-all");
        window.dispatchEvent(new Event("unread-changed"));
        toast("All caught up.");
        refresh();
      } catch (e) { toast(e.message, "error"); }
    }
  }
};
