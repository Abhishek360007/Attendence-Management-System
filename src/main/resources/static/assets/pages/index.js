// Page registry and sidebar navigation. Each page module exports a page object { title, roles?, render }.
import { h, hasRole, session } from "../core.js";
import * as dashboard from "./dashboard.js";
import * as myAttendance from "./my-attendance.js";
import * as leave from "./leave.js";
import * as corrections from "./corrections.js";
import * as notifications from "./notifications.js";
import * as register from "./register.js";
import * as employees from "./employees.js";
import * as employee from "./employee.js";
import * as reports from "./reports.js";
import * as analytics from "./analytics.js";
import * as payroll from "./payroll.js";
import * as organization from "./organization.js";
import * as punches from "./punches.js";
import * as sync from "./sync.js";
import * as users from "./users.js";
import * as audit from "./audit.js";
import * as settings from "./settings.js";
import * as account from "./account.js";

const STAFF = ["ADMIN", "HR"];
const LEADS = ["ADMIN", "HR", "MANAGER"];
const hasProfile = () => !!session.employee;

export const NAV = [
  {
    label: null, items: [
      { path: "/dashboard", label: "Dashboard" },
      { path: "/my-attendance", label: "My attendance", show: hasProfile },
      { path: "/leave", label: "Leave" },
      { path: "/corrections", label: "Punch corrections" },
      { path: "/notifications", label: "Notifications",
        badge: () => (session.unread ? h("span", { class: "badge-count" }, session.unread > 99 ? "99+" : session.unread) : null) }
    ]
  },
  {
    label: "Team", items: [
      { path: "/register", label: "Daily register", roles: LEADS },
      { path: "/employees", label: "Employees", roles: LEADS },
      { path: "/reports", label: "Punctuality report", roles: LEADS },
      { path: "/analytics", label: "Analytics", roles: LEADS }
    ]
  },
  {
    label: "HR", items: [
      { path: "/payroll", label: "Payroll", roles: STAFF },
      { path: "/organization", label: "Departments & schedules", roles: STAFF }
    ]
  },
  {
    label: "System", items: [
      { path: "/punches", label: "Raw punches", roles: STAFF },
      { path: "/sync", label: "Device sync", roles: STAFF },
      { path: "/users", label: "Users", roles: ["ADMIN"] },
      { path: "/audit", label: "Audit log", roles: STAFF },
      { path: "/settings", label: "Settings", roles: ["ADMIN"] }
    ]
  }
];

export function registerPages(route, setNotFound) {
  route("/dashboard", dashboard.page);
  route("/my-attendance", myAttendance.page);
  route("/leave", leave.page);
  route("/corrections", corrections.page);
  route("/notifications", notifications.page);
  route("/register", register.page);
  route("/employees", employees.page);
  route("/employees/:id", employee.page);
  route("/reports", reports.page);
  route("/analytics", analytics.page);
  route("/payroll", payroll.page);
  route("/organization", organization.page);
  route("/punches", punches.page);
  route("/sync", sync.page);
  route("/users", users.page);
  route("/audit", audit.page);
  route("/settings", settings.page);
  route("/account", account.page);
  setNotFound(null);
}

export function homePath() {
  return "/dashboard";
}

export { hasRole };
