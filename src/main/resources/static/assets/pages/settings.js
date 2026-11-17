import { api, card, form, h, load, mount, pageHead, session, toast } from "../core.js";

export const page = {
  title: "Settings",
  roles: ["ADMIN"],
  async render(container) {
    const body = h("div", { class: "card-body" });
    mount(container, pageHead("Settings"), card("Company", body));
    load(body, () => Promise.all([api.get("/api/settings"), api.get("/api/notifications/unread")]), ([s, n]) => {
      const f = form([
        { name: "company_name", label: "Company name", required: true },
        { name: "timezone", label: "Time zone", required: true, help: "Office time zone (e.g. Asia/Kolkata). Punch times are in this zone; it decides what 'today' and 'late' mean." },
        { name: "overtime_rate_multiplier", label: "Overtime pay multiplier", type: "number", min: "0", max: "10", step: "0.05", required: true, help: "1.5 pays overtime at one and a half times the hourly rate." },
        { name: "notify_absence_time", label: "Daily alert time", type: "time", required: true, help: "When absence and late alerts go out each day (and weekly/monthly reports on Mondays and the 1st)." },
        { name: "tracking_start_date", label: "Attendance tracking starts on", type: "date", help: "Days before this are not counted (no absences). Leave empty to start from the first punch received from the devices." },
        { name: "hr_notification_emails", label: "Also email reports to", wide: true, help: "Comma-separated addresses, e.g. hr@company.com, owner@company.com. HR and admin users always get them." },
        { name: "notify_employees_late", label: "Tell employees when they arrive late", type: "checkbox", wide: true }
      ], { ...s, notify_employees_late: s.notify_employees_late === "true" });
      const save = h("button", { class: "btn primary", type: "submit" }, "Save settings");
      const formEl = h("form", { novalidate: true }, f.el,
        h("p", { class: "muted small" }, n.emailEnabled
          ? "Email is set up: notifications are also emailed."
          : "Email is not set up: notifications appear only in the dashboard. To enable email, set MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD and PUBLIC_URL in Render's Environment tab."),
        save);
      formEl.addEventListener("submit", async (e) => {
        e.preventDefault();
        if (!f.validate()) return;
        save.disabled = true;
        f.setError("");
        try {
          const v = f.values();
          const saved = await api.put("/api/settings", { ...v, overtime_rate_multiplier: String(v.overtime_rate_multiplier),
            notify_employees_late: String(v.notify_employees_late), hr_notification_emails: v.hr_notification_emails || "", tracking_start_date: v.tracking_start_date || "" });
          if (session.settings) Object.assign(session.settings, { company_name: saved.company_name, timezone: saved.timezone });
          toast("Settings saved.");
        } catch (err) {
          f.setError(err.message);
        } finally {
          save.disabled = false;
        }
      });
      return formEl;
    });
  }
};
