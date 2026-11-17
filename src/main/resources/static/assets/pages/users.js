import { api, card, fmtDateTime, formModal, h, load, mount, pageHead, person, pill, session, table, titleCase, toast } from "../core.js";

export const ROLE_OPTIONS = [
  { value: "EMPLOYEE", label: "Employee: own attendance, leave and corrections" },
  { value: "MANAGER", label: "Manager: approves their team's requests" },
  { value: "HR", label: "HR: manages employees, leave, payroll and reports" },
  { value: "ADMIN", label: "Admin: everything, including users" }
];

export const page = {
  title: "Users",
  roles: ["ADMIN"],
  async render(container) {
    const body = h("div");
    mount(container,
      pageHead("Users", h("button", { class: "btn primary", type: "button", onclick: () => create() }, "Add user")),
      h("p", { class: "muted", style: "margin-top:-8px" },
        "Sign-in accounts. Employee accounts are usually created from the Employees page so they are linked to a profile."),
      card(null, body));
    refresh();

    function refresh() {
      load(body, () => api.get("/api/users"), (users) => table([
        { label: "Name", value: (u) => person(u.displayName, u.email) },
        { label: "Role", value: (u) => titleCase(u.role) },
        { label: "Status", value: (u) => (u.active ? pill("Active", "ok") : pill("Deactivated", "neutral")) },
        { label: "Last sign-in", value: (u) => (u.lastLoginAt ? fmtDateTime(u.lastLoginAt) : "Never") },
        { label: "", value: (u) => h("span", { style: "display:flex;gap:6px" },
            h("button", { class: "btn small", type: "button", onclick: () => edit(u) }, "Edit"),
            h("button", { class: "btn small", type: "button", onclick: () => reset(u) }, "Reset password")) }
      ], users));
    }

    function create() {
      formModal({
        title: "Add user",
        fields: [
          { name: "name", label: "Name", required: true },
          { name: "email", label: "Email", type: "email", required: true },
          { name: "password", label: "Temporary password", type: "password", required: true, help: "At least 8 characters. Share it with the person; they can change it under My account." },
          { name: "role", label: "Role", type: "select", options: ROLE_OPTIONS, required: true, wide: true }
        ],
        values: { role: "EMPLOYEE" },
        submitLabel: "Create",
        onSubmit: async (v) => { await api.post("/api/users", v); toast("User created."); refresh(); }
      });
    }

    function edit(u) {
      formModal({
        title: `Edit ${u.displayName}`,
        fields: [
          { name: "name", label: "Name", required: true },
          { name: "role", label: "Role", type: "select", options: ROLE_OPTIONS, required: true, wide: true },
          { name: "active", label: "Active (can sign in)", type: "checkbox", wide: true }
        ],
        values: { name: u.displayName, role: u.role, active: u.active },
        intro: u.id === session.me.id ? "This is your own account." : "Changing the role or deactivating signs the user out everywhere.",
        onSubmit: async (v) => { await api.put(`/api/users/${u.id}`, v); toast("User updated."); refresh(); }
      });
    }

    function reset(u) {
      formModal({
        title: `Reset password for ${u.displayName}`,
        fields: [{ name: "password", label: "New password", type: "password", required: true, wide: true }],
        intro: "The user is signed out everywhere and must use the new password.",
        submitLabel: "Reset password",
        onSubmit: async (v) => { await api.post(`/api/users/${u.id}/password`, v); toast("Password reset."); }
      });
    }
  }
};
