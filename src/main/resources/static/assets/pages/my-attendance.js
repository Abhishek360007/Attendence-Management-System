import { currentQuery, h, mount, pageHead, replaceQuery, session } from "../core.js";
import { attendanceMonth } from "./attendance-month.js";

export const page = {
  title: "My attendance",
  async render(container) {
    if (!session.employee) {
      mount(container, pageHead("My attendance"),
        h("div", { class: "alert info" }, "Your account is not linked to an employee profile. Ask HR to link it."));
      return;
    }
    const month = currentQuery().get("month") || undefined;
    mount(container,
      pageHead("My attendance",
        h("a", { class: "btn", href: "#/corrections?new=1" }, "Fix a punch"),
        h("a", { class: "btn primary", href: "#/leave?apply=1" }, "Apply for leave")),
      attendanceMonth(session.employee.id, month, { onMonth: (m) => replaceQuery({ month: m }) }));
  }
};
