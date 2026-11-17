import { api, card, form, h, mount, pageHead, session, titleCase, toast } from "../core.js";

export const page = {
  title: "My account",
  async render(container) {
    const me = session.me;
    const f = form([
      { name: "currentPassword", label: "Current password", type: "password", required: true, autocomplete: "current-password" },
      { name: "newPassword", label: "New password", type: "password", required: true, autocomplete: "new-password", help: "At least 8 characters." }
    ]);
    const save = h("button", { class: "btn primary", type: "submit" }, "Change password");
    const formEl = h("form", { novalidate: true }, f.el, h("div", { style: "margin-top:12px" }, save));
    formEl.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (!f.validate()) return;
      save.disabled = true;
      f.setError("");
      try {
        await api.post("/api/auth/password", f.values());
        f.inputs.currentPassword.value = "";
        f.inputs.newPassword.value = "";
        toast("Password changed.");
      } catch (err) {
        f.setError(err.message);
      } finally {
        save.disabled = false;
      }
    });
    mount(container,
      pageHead("My account"),
      h("div", { class: "two-col" },
        card("Profile", h("div", { class: "card-body" },
          h("p", { style: "margin-top:0" }, h("strong", null, me.displayName), h("br"),
            h("span", { class: "muted" }, me.email)),
          h("p", { style: "margin-bottom:0" }, "Role: ", titleCase(me.role)))),
        card("Password", h("div", { class: "card-body" }, formEl))));
  }
};
