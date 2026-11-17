// Shared building blocks for every page: DOM helper, API client, router, formatting and UI widgets.
// Everything user-supplied is inserted as text (never as HTML).

import { icon } from "./icons.js";

// ---------------------------------------------------------------- DOM
const BOOL_PROPS = new Set(["disabled", "checked", "selected", "required", "hidden", "multiple", "readOnly"]);

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "style") el.setAttribute("style", v);
      else if (k === "dataset") Object.assign(el.dataset, v);
      else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (BOOL_PROPS.has(k)) el[k] = Boolean(v);
      else if (k === "value") el.value = v;
      else el.setAttribute(k, v === true ? "" : String(v));
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

export function mount(el, ...children) {
  clear(el);
  append(el, children);
  return el;
}

// ---------------------------------------------------------------- API
export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) { onUnauthorized = fn; }

function cookie(name) {
  const m = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
  return m ? decodeURIComponent(m[1]) : null;
}

async function request(method, url, body, { quiet401 = false } = {}) {
  const headers = { Accept: "application/json" };
  if (method !== "GET") {
    const token = cookie("XSRF-TOKEN");
    if (token) headers["X-XSRF-TOKEN"] = token;
  }
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let res;
  try {
    res = await fetch(url, { method, headers, credentials: "same-origin",
                             body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, "Cannot reach the server. Check your connection.");
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (res.status === 401 && !quiet401) onUnauthorized();
  if (!res.ok) throw new ApiError(res.status, data?.detail || data?.title || `Request failed (${res.status})`);
  return data;
}

/** Make sure the CSRF cookie exists before the first state-changing request. */
export async function ensureCsrf() {
  if (!cookie("XSRF-TOKEN")) await request("GET", "/api/auth/setup", undefined, { quiet401: true });
}

export const api = {
  get: (url, params) => request("GET", params ? `${url}?${query(params)}` : url),
  post: (url, body) => request("POST", url, body ?? {}),
  put: (url, body) => request("PUT", url, body ?? {}),
  del: (url) => request("DELETE", url),
  raw: request
};

export function query(params) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") q.set(k, v);
  }
  return q.toString();
}

// ---------------------------------------------------------------- session
export const session = { me: null };

export function hasRole(...roles) {
  return !!session.me && roles.includes(session.me.role);
}

// ---------------------------------------------------------------- router
const routes = [];
let notFound = null;

/** path like "/employees/:id"; page = { title, roles?, render(container, params, query) } */
export function route(path, page) {
  const keys = [];
  const pattern = new RegExp("^" + path.replace(/:(\w+)/g, (_, k) => { keys.push(k); return "([^/]+)"; }) + "$");
  routes.push({ path, pattern, keys, page });
}

export function setNotFound(page) { notFound = page; }

export function currentPath() {
  const hash = location.hash.replace(/^#/, "") || "/";
  return hash.split("?")[0];
}

export function currentQuery() {
  const hash = location.hash.replace(/^#/, "");
  return new URLSearchParams(hash.includes("?") ? hash.slice(hash.indexOf("?") + 1) : "");
}

export function navigate(path, params) {
  const q = params ? query(params) : "";
  location.hash = "#" + path + (q ? "?" + q : "");
}

/** Updates the query part of the current URL without re-rendering. */
export function replaceQuery(params) {
  const q = query(params);
  history.replaceState(null, "", "#" + currentPath() + (q ? "?" + q : ""));
}

export function resolve(path) {
  for (const r of routes) {
    const m = path.match(r.pattern);
    if (m) {
      const params = {};
      r.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
      return { page: r.page, params, path: r.path };
    }
  }
  return { page: notFound, params: {}, path: null };
}

// ---------------------------------------------------------------- formatting
const pad = (n) => String(n).padStart(2, "0");
export const todayISO = () => isoDate(new Date());
export const isoDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
export const monthISO = (d = new Date()) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
export const fmtTime = (iso) => (iso ? iso.slice(11, 16) : "–");
export const fmtDateTime = (iso) => (iso ? iso.replace("T", " ").slice(0, 16) : "–");
export function fmtDate(iso) {
  if (!iso) return "–";
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}
export function fmtDay(iso) {
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
}
export function fmtMinutes(m) {
  if (!m) return "–";
  const sign = m < 0 ? "-" : "";
  m = Math.abs(m);
  return `${sign}${Math.floor(m / 60)}h ${pad(m % 60)}m`;
}
export const fmtHours = (m) => (m ? (m / 60).toFixed(1) : "0.0");
export function fmtMonth(ym) {
  const [y, m] = ym.split("-").map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(undefined, { month: "long", year: "numeric" });
}
export const fmtNumber = (n, digits = 0) =>
  n === null || n === undefined ? "–" : Number(n).toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
export const fmtPercent = (n) => (n === null || n === undefined ? "–" : `${Number(n).toFixed(1)}%`);
export const titleCase = (s) => (s || "").toLowerCase().replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());

// ---------------------------------------------------------------- widgets
export function pill(text, kind = "neutral") {
  return h("span", { class: `pill ${kind}` }, text);
}

export function card(title, body, actions) {
  return h("section", { class: "card" },
    title || actions ? h("div", { class: "card-head" }, h("h2", null, title || ""), actions || null) : null,
    body);
}

export function pageHead(title, ...actions) {
  return h("div", { class: "page-head" }, h("h1", null, title), h("div", { class: "actions" }, actions));
}

/** Stat tile. opts.icon names an icon from icons.js; opts.tone is blue | green | amber | red | violet | slate. */
export function stat(label, value, sub, opts = {}) {
  const tone = opts.tone || "slate";
  return h("div", { class: `card stat tone-${tone}` },
    opts.icon ? h("div", { class: "stat-icon" }, icon(opts.icon, 20)) : null,
    h("div", { class: "stat-main" },
      h("div", { class: "label" }, label), h("div", { class: "value" }, value), sub ? h("div", { class: "sub" }, sub) : null));
}

const AVATAR_COLORS = ["#4f46e5", "#0891b2", "#059669", "#d97706", "#db2777", "#7c3aed", "#2563eb", "#dc2626", "#0d9488", "#9333ea"];

/** Initials in a circle; the colour is derived from the name so each person keeps theirs. */
export function avatar(name, size = "") {
  const text = (name || "?").trim();
  const parts = text.split(/\s+/).filter(Boolean);
  const initials = ((parts[0]?.[0] || "?") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
  let hash = 0;
  for (const ch of text) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return h("span", { class: `avatar ${size}`, style: `background:${AVATAR_COLORS[hash % AVATAR_COLORS.length]}`, "aria-hidden": "true" }, initials);
}

/** Avatar + name (+ optional second line), optionally linked. */
export function person(name, sub, href) {
  const text = h("span", { class: "person-text" }, href ? h("a", { href }, name) : h("strong", { style: "font-weight:550" }, name),
    sub ? h("span", { class: "muted" }, sub) : null);
  return h("span", { class: "person" }, avatar(name, "sm"), text);
}

/**
 * columns: [{ label, value: row => string|Node, class?, csv?: row => value }]
 */
export function table(columns, rows, { empty = "Nothing to show.", onRowClick } = {}) {
  if (!rows || rows.length === 0) return h("div", { class: "empty" }, icon("inbox", 28), empty);
  return h("div", { class: "table-wrap" },
    h("table", null,
      h("thead", null, h("tr", null, columns.map((c) => h("th", { class: c.class || "" }, c.label)))),
      h("tbody", null, rows.map((r) => h("tr", {
        class: onRowClick ? "clickable" : "",
        onclick: onRowClick ? () => onRowClick(r) : null
      }, columns.map((c) => h("td", { class: c.class || "" }, c.value(r) ?? "–")))))));
}

export function errorBox(message) {
  return h("div", { class: "alert error" }, message);
}

export function loading() {
  return h("div", { class: "empty" }, "Loading…");
}

let toastHost;
export function toast(message, kind = "info") {
  if (!toastHost) document.body.append(toastHost = h("div", { class: "toasts", role: "status" }));
  const t = h("div", { class: `toast ${kind}` }, message);
  toastHost.append(t);
  setTimeout(() => t.remove(), kind === "error" ? 6000 : 3500);
}

export function modal({ title, body, actions = [], onClose }) {
  const close = () => { overlay.remove(); document.removeEventListener("keydown", esc); onClose?.(); };
  const esc = (e) => { if (e.key === "Escape") close(); };
  const overlay = h("div", { class: "overlay", onmousedown: (e) => { if (e.target === overlay) close(); } },
    h("div", { class: "modal", role: "dialog", "aria-modal": "true", "aria-label": title },
      h("header", null, h("h2", { style: "margin:0" }, title),
        h("button", { class: "icon-btn", type: "button", "aria-label": "Close", onclick: close }, "✕")),
      h("div", { class: "modal-body" }, body),
      actions.length ? h("footer", null, actions) : null));
  document.addEventListener("keydown", esc);
  document.body.append(overlay);
  overlay.querySelector("input, select, textarea, button.primary")?.focus();
  return { close, overlay };
}

export function confirmDialog(message, { confirmLabel = "Confirm", danger = false } = {}) {
  return new Promise((resolveFn) => {
    let done = false;
    const m = modal({
      title: "Please confirm",
      body: h("p", { style: "margin:0" }, message),
      onClose: () => { if (!done) resolveFn(false); },
      actions: [
        h("button", { class: "btn", type: "button", onclick: () => m.close() }, "Cancel"),
        h("button", { class: `btn primary${danger ? " danger" : ""}`, type: "button",
                      onclick: () => { done = true; m.close(); resolveFn(true); } }, confirmLabel)
      ]
    });
  });
}

/**
 * fields: [{ name, label, type, options:[{value,label}], required, wide, help, min, max, step, placeholder }]
 * Returns { el, values(), setError(msg) }.
 */
export function form(fields, values = {}) {
  const inputs = {};
  const error = h("div", { class: "alert error hidden" });
  const grid = h("div", { class: "form-grid" }, fields.map((f) => {
    const v = values[f.name];
    let input;
    if (f.type === "select") {
      input = h("select", { name: f.name, required: f.required },
        f.placeholder !== undefined ? h("option", { value: "" }, f.placeholder) : null,
        f.options.map((o) => h("option", { value: o.value, selected: String(o.value) === String(v ?? "") }, o.label)));
    } else if (f.type === "textarea") {
      input = h("textarea", { name: f.name, required: f.required, placeholder: f.placeholder });
      input.value = v ?? "";
    } else if (f.type === "checkbox") {
      input = h("input", { type: "checkbox", name: f.name, checked: !!v });
      inputs[f.name] = input;
      return h("label", { class: `check${f.wide ? " wide" : ""}` }, input, f.label);
    } else {
      input = h("input", { type: f.type || "text", name: f.name, required: f.required, min: f.min, max: f.max,
                           step: f.step, placeholder: f.placeholder, autocomplete: f.autocomplete || "off",
                           value: v ?? "" });
    }
    inputs[f.name] = input;
    return h("label", { class: `field${f.wide ? " wide" : ""}` }, f.label + (f.required ? " *" : ""), input,
      f.help ? h("span", { class: "small muted" }, f.help) : null);
  }));
  const el = h("div", null, error, grid);
  return {
    el,
    inputs,
    values() {
      const out = {};
      for (const f of fields) {
        const i = inputs[f.name];
        if (f.type === "checkbox") out[f.name] = i.checked;
        else if (f.type === "number") out[f.name] = i.value === "" ? null : Number(i.value);
        else out[f.name] = i.value === "" ? null : i.value;
      }
      return out;
    },
    validate() {
      for (const f of fields) {
        const i = inputs[f.name];
        if (!i.checkValidity()) { i.reportValidity(); return false; }
      }
      return true;
    },
    setError(msg) {
      error.textContent = msg || "";
      error.classList.toggle("hidden", !msg);
    }
  };
}

/** A modal containing a form. onSubmit(values) may throw ApiError; its message is shown in the form. */
export function formModal({ title, fields, values, submitLabel = "Save", onSubmit, intro }) {
  const f = form(fields, values);
  const submit = h("button", { class: "btn primary", type: "submit" }, submitLabel);
  const formEl = h("form", { novalidate: true }, intro ? h("p", { class: "muted", style: "margin-top:0" }, intro) : null, f.el);
  let m;
  formEl.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!f.validate()) return;
    submit.disabled = true;
    f.setError("");
    try {
      await onSubmit(f.values());
      m.close();
    } catch (err) {
      f.setError(err.message);
    } finally {
      submit.disabled = false;
    }
  });
  m = modal({
    title,
    body: formEl,
    actions: [
      h("button", { class: "btn", type: "button", onclick: () => m.close() }, "Cancel"),
      h("button", { class: "btn primary", type: "button", onclick: () => formEl.requestSubmit() }, submitLabel)
    ]
  });
  return m;
}

export function tabs(items, active, onChange) {
  return h("div", { class: "tabs", role: "tablist" }, items.map(([key, label]) =>
    h("button", { class: "tab", role: "tab", type: "button", "aria-selected": String(key === active),
                  onclick: () => onChange(key) }, label)));
}

/** rows: array of arrays. A BOM makes Excel open the file as UTF-8; a leading quote stops formula injection. */
export function downloadCsv(filename, header, rows) {
  const cell = (v) => {
    let s = v === null || v === undefined ? "" : String(v);
    if (/^[=+\-@\t\r]/.test(s) && !/^-?\d+(\.\d+)?$/.test(s)) s = "'" + s;
    return `"${s.replace(/"/g, '""')}"`;
  };
  const text = [header, ...rows].map((r) => r.map(cell).join(",")).join("\r\n");
  const blob = new Blob([String.fromCharCode(0xfeff) + text], { type: "text/csv;charset=utf-8" });
  const a = h("a", { href: URL.createObjectURL(blob), download: filename });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

/** Runs an async loader, showing a spinner then the result or an error inside container. */
export async function load(container, loader, render) {
  mount(container, loading());
  try {
    const data = await loader();
    mount(container, render(data));
  } catch (e) {
    if (e.status !== 401) mount(container, errorBox(e.message));
  }
}
export { icon };
