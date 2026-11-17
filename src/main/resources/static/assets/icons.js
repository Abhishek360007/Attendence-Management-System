// Simple 24x24 line icons (stroke = currentColor), drawn for this app.
const PATHS = {
  dashboard: "M4 4h7v7H4zM13 4h7v4h-7zM13 10h7v10h-7zM4 13h7v7H4z",
  calendar: "M4 6h16v14H4zM4 10h16M8 3v4M16 3v4M8 14h2M14 14h2M8 17h2",
  leave: "M3 20h18M6 20c0-5 2.5-9 6-11M12 9c3.5 2 6 6 6 11M12 9V4M9 6.5 12 4l3 2.5",
  wrench: "M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.6 2.6-2.4-.6-.6-2.4z",
  bell: "M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15zM10 20a2 2 0 0 0 4 0",
  clipboard: "M8 4h8v3H8zM6 5.5H5v15h14v-15h-1M9 12l2 2 4-4M9 17h6",
  users: "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M16 4.3a3.5 3.5 0 0 1 0 6.4M18 14.5c1.8.8 3 2.9 3 5.5",
  report: "M5 3h10l4 4v14H5zM15 3v4h4M9 17v-4M12 17v-7M15 17v-2",
  chart: "M4 20h16M6 16l4-5 3 3 5-7M18 7h-3M18 7v3",
  wallet: "M4 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4zM4 7l11-3v3M16 13.5h.01",
  building: "M5 21V4h10v17M15 9h4v12M9 8h2M9 12h2M9 16h2M3 21h18",
  fingerprint: "M12 11v4M8.5 7.5A5 5 0 0 1 17 11v2M7 11a5 5 0 0 0 .5 2.3M9 15c.6 2 1.7 3.5 3 4.5M12 4a7 7 0 0 1 7 7v1.5M5 11a7 7 0 0 1 2-5M15 15.5c-.3 1.6-.9 3-1.7 4",
  sync: "M20 11a8 8 0 0 0-14.3-4.9L4 8M4 4v4h4M4 13a8 8 0 0 0 14.3 4.9L20 16M20 20v-4h-4",
  userCog: "M10 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM4 20c0-3.3 2.7-6 6-6 1.2 0 2.3.3 3.2.9M18 15v-1.5M18 21v-1.5M15.4 16.5l-1.3-.7M21.9 18.7l-1.3-.7M15.4 18l-1.3.7M21.9 15.8l-1.3.7M18 18.2a.5.5 0 1 0 0-.01",
  shield: "M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6zM9 12l2 2 4-4",
  settings: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
  user: "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c0-4 3.6-7 8-7s8 3 8 7",
  check: "M5 12.5l4.5 4.5L19 7.5",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2",
  xCircle: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9 9l6 6M15 9l-6 6",
  sun: "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
  plane: "M3 13l7-2 4-7 2 1-2 7 6 2v2l-7-1-3 5H8l1-5-6-1z",
  hourglass: "M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9",
  trendUp: "M3 17l6-6 4 4 8-8M15 7h6v6",
  alert: "M12 3l10 17H2zM12 10v4M12 17h.01",
  logout: "M9 21H5V3h4M16 17l5-5-5-5M21 12H9",
  menu: "M4 7h16M4 12h16M4 17h16",
  money: "M3 7h18v10H3zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 10v4M18 10v4",
  inbox: "M3 13l3-8h12l3 8v6H3zM3 13h5l1 3h6l1-3h5"
};

export function icon(name, size = 18) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", size);
  svg.setAttribute("height", size);
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", "icon");
  const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
  p.setAttribute("d", PATHS[name] || PATHS.dashboard);
  svg.append(p);
  return svg;
}
