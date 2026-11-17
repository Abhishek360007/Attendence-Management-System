import { api, card, fmtDateTime, h, load, mount, navigate, pageHead, toast } from "../core.js";

export const page = {
  title: "Notifications",
  async render(container) {
    const body = h("div");
    const readAll = h("button", { class: "btn", type: "button", onclick: markAll }, "Mark all as read");
    const emailNote = h("div");
    mount(container, pageHead("Notifications", readAll), emailNote, card(null, body));
    api.get("/api/notifications/unread").then((r) => {
      if (!r.emailEnabled) mount(emailNote, h("p", { class: "muted small", style: "margin-top:-8px" }, "Email is not set up, so notifications appear only here."));
    }).catch(() => {});
    refresh();

    function refresh() {
      load(body, () => api.get("/api/notifications"), (rows) => rows.length
        ? h("div", null, rows.map((n) => h("div", {
            style: `padding:12px 16px;border-bottom:1px solid var(--border);display:grid;gap:4px;cursor:${n.link ? "pointer" : "default"};${n.readAt ? "" : "background:var(--accent-soft)"}`,
            onclick: () => open(n)
          },
          h("div", { style: "display:flex;justify-content:space-between;gap:12px" },
            h("strong", null, n.title), h("span", { class: "muted small", style: "white-space:nowrap" }, fmtDateTime(n.createdAt))),
          n.body ? h("div", { class: "muted", style: "white-space:pre-line" }, n.body) : null)))
        : h("div", { class: "empty" }, "No notifications yet. You will be told here about leave and correction requests, decisions, absences and reports."));
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
