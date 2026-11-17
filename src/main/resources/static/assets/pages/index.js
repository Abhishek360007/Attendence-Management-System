// Page registry and sidebar navigation. Each page module exports a page object { title, roles?, render }.
import { hasRole } from "../core.js";
import * as punches from "./punches.js";
import * as sync from "./sync.js";
import * as users from "./users.js";
import * as audit from "./audit.js";
import * as account from "./account.js";

const STAFF = ["ADMIN", "HR"];

export const NAV = [
  {
    label: null, items: [
      { path: "/punches", label: "Punches", roles: [...STAFF] }
    ]
  },
  {
    label: "System", items: [
      { path: "/sync", label: "Device sync", roles: [...STAFF] },
      { path: "/users", label: "Users", roles: ["ADMIN"] },
      { path: "/audit", label: "Audit log", roles: [...STAFF] }
    ]
  }
];

export function registerPages(route, setNotFound) {
  route("/punches", punches.page);
  route("/sync", sync.page);
  route("/users", users.page);
  route("/audit", audit.page);
  route("/account", account.page);
  setNotFound(null);
}

export function homePath() {
  return hasRole(...STAFF) ? "/punches" : "/account";
}
