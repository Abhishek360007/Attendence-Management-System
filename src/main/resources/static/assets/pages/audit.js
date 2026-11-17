import { api, card, currentQuery, fmtDateTime, h, load, mount, pageHead, replaceQuery, table, titleCase } from "../core.js";

const PAGE_SIZE = 100;

export const page = {
  title: "Audit log",
  roles: ["ADMIN", "HR"],
  async render(container) {
    const q = currentQuery();
    const state = { entityType: q.get("entityType") || "", entityId: q.get("entityId") || "", page: 0 };
    const body = h("div");
    const type = h("select", null,
      [["", "Everything"], ["user", "Users"], ["employee", "Employees"], ["department", "Departments"],
       ["schedule", "Schedules"], ["holiday", "Holidays"], ["leave", "Leave"], ["leave_type", "Leave types"],
       ["correction", "Corrections"], ["punch", "Punches"], ["settings", "Settings"]]
        .map(([v, l]) => h("option", { value: v, selected: v === state.entityType }, l)));
    const id = h("input", { placeholder: "Any", size: "8", value: state.entityId });
    const filters = h("form", { class: "filters" },
      h("label", { class: "field" }, "Record type", type),
      h("label", { class: "field" }, "Record ID", id),
      h("button", { class: "btn primary", type: "submit" }, "Apply"));
    filters.addEventListener("submit", (e) => {
      e.preventDefault();
      Object.assign(state, { entityType: type.value, entityId: id.value.trim(), page: 0 });
      refresh();
    });
    mount(container,
      pageHead("Audit log"),
      h("p", { class: "muted", style: "margin-top:-8px" }, "Every change made in the system: who, what and when. Entries cannot be edited or deleted."),
      card(null, [filters, body]));
    refresh();

    function refresh() {
      replaceQuery({ entityType: state.entityType, entityId: state.entityId });
      load(body, () => api.get("/api/audit", { entityType: state.entityType, entityId: state.entityId,
                                               page: state.page, size: PAGE_SIZE }),
        (rows) => [
          table([
            { label: "When", value: (r) => fmtDateTime(r.at) },
            { label: "Who", value: (r) => r.actorName },
            { label: "Action", value: (r) => titleCase(r.action) },
            { label: "Record", value: (r) => `${titleCase(r.entityType)}${r.entityId ? " #" + r.entityId : ""}` },
            { label: "Details", value: (r) => h("span", { class: "muted" }, r.details || ""), class: "wrap" }
          ], rows, { empty: "No matching changes." }),
          state.page > 0 || rows.length === PAGE_SIZE
            ? h("div", { class: "pager" }, `Page ${state.page + 1}`,
                h("button", { class: "btn small", disabled: state.page === 0, onclick: () => { state.page--; refresh(); } }, "Newer"),
                h("button", { class: "btn small", disabled: rows.length < PAGE_SIZE, onclick: () => { state.page++; refresh(); } }, "Older"))
            : null
        ]);
    }
  }
};
