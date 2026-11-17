import {
  api, ApiError, currentPath, currentQuery, ensureCsrf, h, hasRole, mount, navigate, resolve, route, session,
  setNotFound, setUnauthorizedHandler, errorBox
} from "./core.js";
import { NAV, registerPages, homePath } from "./pages/index.js";

const root = document.getElementById("app");
const LOGO = () => h("span", { class: "logo" },
  (() => {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("width", "16"); svg.setAttribute("height", "16"); svg.setAttribute("viewBox", "0 0 32 32");
    const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
    p.setAttribute("d", "M8 16.5l5 5L24 10.5"); p.setAttribute("stroke", "#fff"); p.setAttribute("stroke-width", "4");
    p.setAttribute("fill", "none"); p.setAttribute("stroke-linecap", "round"); p.setAttribute("stroke-linejoin", "round");
    svg.append(p);
    return svg;
  })());

let shell = null;          // { el, content, nav }
let renderToken = 0;

// ---------------------------------------------------------------- auth screens
function authCard(title, intro, formEl) {
  return h("div", { class: "auth" },
    h("div", { class: "brand", style: "justify-content:center;padding-bottom:20px" }, LOGO(), "Attendance"),
    h("div", { class: "card" }, h("div", { class: "card-body" }, h("h2", null, title), h("p", null, intro), formEl)));
}

function loginScreen(message) {
  shell = null;
  const email = h("input", { type: "email", placeholder: "Email", autocomplete: "username", required: true });
  const password = h("input", { type: "password", placeholder: "Password", autocomplete: "current-password", required: true });
  const error = h("div", { class: `alert error${message ? "" : " hidden"}`, style: "margin:0" }, message || "");
  const button = h("button", { class: "btn primary", type: "submit" }, "Sign in");
  const formEl = h("form", null, email, password, button, error);
  formEl.addEventListener("submit", async (e) => {
    e.preventDefault();
    button.disabled = true;
    try {
      await ensureCsrf();
      session.me = await api.raw("POST", "/api/auth/login", { email: email.value.trim(), password: password.value },
                                 { quiet401: true });
      start();
    } catch (err) {
      error.textContent = err.message;
      error.classList.remove("hidden");
      button.disabled = false;
    }
  });
  mount(root, authCard("Sign in", "Use the account your administrator created for you.", formEl));
  email.focus();
}

function setupScreen() {
  shell = null;
  const key = h("input", { type: "password", placeholder: "Setup key (the API_KEY from Render)", autocomplete: "off" });
  const name = h("input", { placeholder: "Your name", required: true });
  const email = h("input", { type: "email", placeholder: "Email", autocomplete: "username", required: true });
  const password = h("input", { type: "password", placeholder: "Password (8+ characters)", minlength: "8",
                                autocomplete: "new-password", required: true });
  const error = h("div", { class: "alert error hidden", style: "margin:0" });
  const button = h("button", { class: "btn primary", type: "submit" }, "Create admin account");
  const formEl = h("form", null, key, name, email, password, button, error);
  formEl.addEventListener("submit", async (e) => {
    e.preventDefault();
    button.disabled = true;
    try {
      await ensureCsrf();
      session.me = await api.raw("POST", "/api/auth/setup", {
        setupKey: key.value.trim(), name: name.value.trim(), email: email.value.trim(), password: password.value
      }, { quiet401: true });
      start();
    } catch (err) {
      error.textContent = err.message;
      error.classList.remove("hidden");
      button.disabled = false;
    }
  });
  mount(root, authCard("Welcome", "Create the first administrator account. You need the API_KEY value from Render " +
    "(your service → Environment) once, to prove you own this installation.", formEl));
  name.focus();
}

// ---------------------------------------------------------------- shell
function buildShell() {
  const content = h("main", { class: "content", id: "content" });
  const nav = h("nav", { class: "nav", "aria-label": "Main" });
  const shellEl = h("div", { class: "shell" });
  const closeNav = () => shellEl.classList.remove("nav-open");
  const sidebar = h("aside", { class: "sidebar" },
    h("div", { class: "brand" }, LOGO(), "Attendance"),
    nav,
    h("div", { class: "me" },
      h("strong", null, session.me.displayName),
      h("span", { class: "muted" }, session.me.email), h("br"),
      h("a", { href: "#/account", onclick: closeNav }, "My account"), " · ",
      h("button", { class: "btn link small", type: "button", onclick: logout }, "Sign out")));
  const topbar = h("header", { class: "topbar" },
    h("button", { class: "icon-btn", type: "button", "aria-label": "Menu",
                  onclick: () => shellEl.classList.toggle("nav-open") }, "☰"),
    h("strong", null, "Attendance"));
  mount(shellEl, sidebar, h("div", { style: "min-width:0" }, topbar, content));
  nav.addEventListener("click", (e) => { if (e.target.closest("a")) closeNav(); });
  mount(root, shellEl);
  shell = { el: shellEl, content, nav };
  renderNav();
}

export function renderNav() {
  if (!shell) return;
  const path = currentPath();
  mount(shell.nav, NAV.map((group) => {
    const items = group.items.filter((i) => !i.roles || hasRole(...i.roles));
    if (!items.length) return null;
    return [group.label ? h("div", { class: "nav-group" }, group.label) : null,
      items.map((i) => h("a", {
        href: "#" + i.path,
        class: path === i.path || (i.path !== "/" && path.startsWith(i.path + "/")) ? "active" : "",
        "aria-current": path === i.path ? "page" : null
      }, i.label, i.badge ? i.badge() : null))];
  }));
}

async function logout() {
  try { await api.post("/api/auth/logout"); } catch { /* already signed out */ }
  session.me = null;
  location.hash = "";
  loginScreen();
}

async function render() {
  if (!session.me) return;
  if (!shell) buildShell();
  const token = ++renderToken;
  const path = currentPath();
  if (path === "/") { navigate(homePath()); return; }
  const { page, params } = resolve(path);
  renderNav();
  if (!page || (page.roles && !hasRole(...page.roles))) {
    mount(shell.content, errorBox(page ? "You do not have access to this page." : "Page not found."));
    return;
  }
  document.title = `${page.title} · Attendance`;
  try {
    await page.render(shell.content, params, currentQuery(), () => token !== renderToken);
  } catch (e) {
    if (token === renderToken && !(e instanceof ApiError && e.status === 401)) mount(shell.content, errorBox(e.message));
  }
  window.scrollTo(0, 0);
}

// ---------------------------------------------------------------- start
async function start() {
  try {
    session.me = await api.raw("GET", "/api/auth/me", undefined, { quiet401: true });
  } catch (e) {
    if (e.status === 401 || e.status === 403) {
      const setup = await api.raw("GET", "/api/auth/setup", undefined, { quiet401: true }).catch(() => ({ needed: false }));
      return setup.needed ? setupScreen() : loginScreen();
    }
    mount(root, h("div", { class: "auth" }, errorBox(e.message)));
    return;
  }
  shell = null;
  render();
}

setUnauthorizedHandler(() => {
  if (session.me) {
    session.me = null;
    loginScreen("Your session has ended. Please sign in again.");
  }
});
registerPages(route, setNotFound);
window.addEventListener("hashchange", render);
start();
