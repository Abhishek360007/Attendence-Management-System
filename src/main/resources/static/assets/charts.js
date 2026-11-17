// Small single-series SVG/HTML charts with hover tooltips. Text uses text tokens; the series colour marks identity.
import { h } from "./core.js";

const SVG = "http://www.w3.org/2000/svg";
const s = (tag, attrs = {}) => {
  const el = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  return el;
};

function tooltip(host) {
  const tip = h("div", { class: "chart-tip hidden", role: "tooltip" });
  host.append(tip);
  return {
    show(x, y, title, value) {
      tip.replaceChildren(h("strong", null, value), title);
      tip.classList.remove("hidden");
      const w = tip.offsetWidth;
      const max = host.clientWidth - w - 4;
      tip.style.left = Math.max(4, Math.min(x - w / 2, max)) + "px";
      tip.style.top = Math.max(0, y - tip.offsetHeight - 10) + "px";
    },
    hide() { tip.classList.add("hidden"); }
  };
}

/**
 * Line chart for a value over time. points: [{ label, value|null }]. Nulls leave a gap.
 * yMax fixes the scale (e.g. 100 for percentages); otherwise the data max is rounded up.
 */
export function lineChart(points, { format = (v) => v, yMax, height = 220, ariaLabel = "Line chart" } = {}) {
  const host = h("div", { class: "chart" });
  const W = 640, H = height, L = 40, R = 16, T = 12, B = 28;
  const values = points.map((p) => p.value).filter((v) => v !== null && v !== undefined);
  const top = yMax ?? niceCeil(Math.max(1, ...values));
  const x = (i) => L + (points.length === 1 ? (W - L - R) / 2 : (i * (W - L - R)) / (points.length - 1));
  const y = (v) => T + (H - T - B) * (1 - v / top);
  const svg = s("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": ariaLabel });

  for (let k = 0; k <= 4; k++) {
    const v = (top * k) / 4;
    svg.append(s("line", { x1: L, x2: W - R, y1: y(v), y2: y(v), class: k === 0 ? "baseline" : "grid-line" }));
    const t = s("text", { x: L - 6, y: y(v) + 4, "text-anchor": "end" });
    t.textContent = format(v);
    svg.append(t);
  }
  points.forEach((p, i) => {
    const t = s("text", { x: x(i), y: H - 8, "text-anchor": "middle" });
    t.textContent = p.label;
    svg.append(t);
  });
  // A missing value breaks the line: the next point starts a new segment.
  let d = "";
  let pen = false;
  points.forEach((p, i) => {
    if (p.value === null || p.value === undefined) { pen = false; return; }
    d += `${pen ? "L" : "M"}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`;
    pen = true;
  });
  if (d) svg.append(s("path", { d, class: "series-line" }));
  const cross = s("line", { y1: T, y2: H - B, class: "crosshair", visibility: "hidden" });
  svg.append(cross);
  points.forEach((p, i) => {
    if (p.value === null || p.value === undefined) return;
    svg.append(s("circle", { cx: x(i), cy: y(p.value), r: 4, class: "series-dot" }));
  });
  const tip = tooltip(host);
  const band = (W - L - R) / Math.max(1, points.length - 1);
  points.forEach((p, i) => {
    const hit = s("rect", { x: x(i) - band / 2, y: T, width: band, height: H - T - B, class: "hit" });
    hit.addEventListener("mouseenter", () => {
      cross.setAttribute("x1", x(i)); cross.setAttribute("x2", x(i)); cross.setAttribute("visibility", "visible");
      const scale = svg.clientWidth / W;
      tip.show(x(i) * scale + 16, (p.value === null ? T : y(p.value)) * scale + 8, p.label, p.value === null ? "No data" : format(p.value));
    });
    hit.addEventListener("mouseleave", () => { cross.setAttribute("visibility", "hidden"); tip.hide(); });
    svg.append(hit);
  });
  host.prepend(svg);
  return host;
}

/** Horizontal bars for comparing categories. rows: [{ label, value, title? }] */
export function barList(rows, { format = (v) => v, max } = {}) {
  const top = max ?? Math.max(1, ...rows.map((r) => r.value ?? 0));
  const host = h("div", { class: "hbars", style: "position:relative" });
  const tip = tooltip(host);
  rows.forEach((r) => {
    const pct = r.value === null || r.value === undefined ? 0 : Math.max(0, Math.min(100, (r.value / top) * 100));
    const row = h("div", { class: "hbar" },
      h("span", { class: "name", title: r.label }, r.label),
      h("div", { class: "track" }, h("div", { class: "fill", style: `width:${pct}%` })),
      h("span", { class: "val" }, r.value === null || r.value === undefined ? "–" : format(r.value)));
    row.addEventListener("mousemove", (e) => {
      const box = host.getBoundingClientRect();
      tip.show(e.clientX - box.left, e.clientY - box.top, r.title || r.label, r.value === null ? "No data" : format(r.value));
    });
    row.addEventListener("mouseleave", () => tip.hide());
    host.append(row);
  });
  return host;
}

/** Collapsible table with the chart's numbers. */
export function tableView(header, rows) {
  return h("details", { class: "table-view" }, h("summary", null, "Show as table"),
    h("div", { class: "table-wrap" }, h("table", null,
      h("thead", null, h("tr", null, header.map((c, i) => h("th", { class: i ? "num" : "" }, c)))),
      h("tbody", null, rows.map((r) => h("tr", null, r.map((c, i) => h("td", { class: i ? "num" : "" }, c))))))));
}

function niceCeil(v) {
  const mag = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * mag >= v) return m * mag;
  return 10 * mag;
}
