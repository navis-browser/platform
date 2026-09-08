/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

const SUPPORT_DETAILS = ["cpuDetails", "adapterDescription2", "adapterRAM", "graphicsFeatures", "graphicsFailureDetails", "graphicsCrashGuards", "sandboxFeatures", "win32kLockdown", "modifiedPreferences", "allExtensions"];

export function renderAdditionalSupport(t, escape) {
  return `<section class="card diagnostics-card"><h2>${escape(t("diagnostics.moreDetails"))}</h2><dl>${SUPPORT_DETAILS.map(key => `<div class="diagnostic-row" data-diagnostic-label="diagnostics.${key}" data-diagnostic-dynamic><dt>${escape(t(`diagnostics.${key}`))}</dt><dd class="diagnostic-multiline">${escape(t("common.loading"))}</dd></div>`).join("")}</dl></section>`;
}

export function renderAdditionalProductPage(page, t, escape) {
  if (page.key === "processes") {
    return `<p>${escape(t("processes.description"))}</p><div class="management-toolbar"><button id="process-refresh" class="management-action" type="button">${escape(t("processes.refresh"))}</button></div><div class="process-table-scroll"><table class="process-table"><thead><tr>${["process", "memory", "cpu", "threads", "actions"].map(key => `<th scope="col">${escape(t(`processes.${key}`))}</th>`).join("")}</tr></thead><tbody id="process-list"></tbody></table></div><p id="management-status" class="management-status" role="status" aria-live="polite">${escape(t("management.loading"))}</p><dialog id="process-end-dialog" class="settings-dialog" aria-labelledby="process-end-heading"><h2 id="process-end-heading">${escape(t("processes.end"))}</h2><p id="process-end-title"></p><p>${escape(t("processes.endWarning"))}</p><div class="settings-dialog-actions"><button id="process-end-cancel" type="button" class="settings-action">${escape(t("common.cancel"))}</button><button id="process-end-confirm" type="button" class="settings-action">${escape(t("processes.end"))}</button></div></dialog>`;
  }
  if (page.key === "credits") {
    return `<section class="card"><h2>Navis</h2><p>${escape(t("settings.aboutCopyright"))}</p><p>${escape(t("credits.notice"))}</p><p><a href="https://www.mozilla.org/MPL/2.0/" target="_blank" rel="noopener noreferrer">Mozilla Public License 2.0</a></p><h2>uBlock Origin</h2><p>Copyright Raymond Hill and contributors · GNU General Public License version 3</p><p><a href="https://github.com/gorhill/uBlock" target="_blank" rel="noopener noreferrer">uBlock Origin</a></p></section><section class="card"><h2>${escape(t("credits.packagedNotices"))}</h2><pre id="credits-notices" class="credits-notices"></pre><p id="management-status" class="management-status" role="status" aria-live="polite">${escape(t("management.loading"))}</p></section>`;
  }
  return null;
}

export const PRODUCT_PAGES_STYLE = `
  .diagnostic-multiline { white-space: pre-wrap; overflow-wrap: anywhere; }
  .process-table-scroll { overflow-x: auto; margin-top: 16px; border-radius: 16px; background: var(--surface); box-shadow: 0 1px 2px rgb(31 31 31 / 18%); }
  .process-table { width: 100%; border-collapse: collapse; text-align: start; }
  .process-table th, .process-table td { padding: 14px 16px; border-bottom: 1px solid var(--line); text-align: start; vertical-align: middle; }
  .process-table th { color: var(--muted); font-weight: 500; }
  .process-table td:first-child { min-width: 200px; }
  .process-table strong, .process-table small { display: block; max-width: 400px; overflow-wrap: anywhere; }
  .process-table small { color: var(--muted); }
  .process-table tr:last-child td { border-bottom: 0; }
  .credits-notices { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
`;

export const PRODUCT_PAGES_SCRIPT = String.raw`
(() => {
  const pageKey = document.documentElement.dataset.pageKey;
  const t = window.NavisL10n.text;
  const status = document.getElementById("management-status");
  const list = document.getElementById("process-list");
  const dialog = document.getElementById("process-end-dialog");
  const refresh = document.getElementById("process-refresh");
  let busy = false, timer = null, timeout = null, selectedToken = "";
  const dispatch = (command, value) => {
    if (busy) return;
    busy = true;
    if (refresh) refresh.disabled = true;
    clearTimeout(timeout);
    timeout = setTimeout(() => { busy = false; if (refresh) refresh.disabled = false; status.textContent = t("management.failed"); }, 10000);
    document.dispatchEvent(new CustomEvent("NavisManagementCommand", { detail: { command, value }, bubbles: true }));
  };
  const typeLabel = type => {
    const known = ["browser", "web", "webIsolated", "file", "withCoopCoep", "webServiceWorker", "gpu", "socket", "utility", "rdd", "extension", "privilegedabout", "preallocated"];
    return known.includes(type) ? t("processes.type." + type) : type;
  };
  const render = processes => {
    const fragment = document.createDocumentFragment();
    for (const process of processes) {
      const row = document.createElement("tr"), identity = document.createElement("td"), title = document.createElement("strong"), detail = document.createElement("small");
      title.textContent = process.title || process.origin || typeLabel(process.type);
      detail.textContent = typeLabel(process.type) + (process.origin ? " · " + process.origin : "");
      identity.append(title, detail); row.append(identity);
      for (const value of [window.NavisL10n.number(Math.round(process.memoryBytes / 1024 / 1024)) + " MiB", process.cpuPercent === null ? t("processes.measuring") : window.NavisL10n.number(process.cpuPercent) + "%", window.NavisL10n.number(process.threadCount)]) {
        const cell = document.createElement("td"); cell.textContent = value; row.append(cell);
      }
      const action = document.createElement("td");
      if (process.canEnd) {
        const button = document.createElement("button"); button.type = "button"; button.className = "management-action"; button.textContent = t("processes.end");
        button.addEventListener("click", () => { selectedToken = process.token; document.getElementById("process-end-title").textContent = title.textContent; dialog.showModal(); document.getElementById("process-end-cancel").focus(); });
        action.append(button);
      } else { action.textContent = t("processes.protected"); }
      row.append(action); fragment.append(row);
    }
    list.replaceChildren(fragment);
  };
  window.addEventListener("NavisManagementState", event => {
    const state = event.detail;
    if (state?.pageKey !== pageKey) return;
    clearTimeout(timeout); busy = false; if (refresh) refresh.disabled = false;
    if (state.outcome !== "ready") { status.textContent = t("management.failed"); return; }
    if (pageKey === "processes" && Array.isArray(state.processes)) render(state.processes);
    if (pageKey === "credits" && typeof state.licenses === "string") document.getElementById("credits-notices").textContent = state.licenses;
    status.textContent = "";
  });
  refresh?.addEventListener("click", () => dispatch("processes:get"));
  document.getElementById("process-end-cancel")?.addEventListener("click", () => dialog.close());
  document.getElementById("process-end-confirm")?.addEventListener("click", () => { dialog.close(); dispatch("processes:end", { token: selectedToken }); selectedToken = ""; });
  const start = () => {
    dispatch(pageKey + ":get");
    if (pageKey === "processes") { clearInterval(timer); timer = setInterval(() => { if (!document.hidden && !dialog.open && !busy) dispatch("processes:get"); }, 2000); }
  };
  window.addEventListener("pageshow", start);
  window.addEventListener("pagehide", () => { clearInterval(timer); clearTimeout(timeout); busy = false; });
})();`;
