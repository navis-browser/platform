/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import {
  NAVIS_CATALOGS,
  createNavisLocalizer,
  normalizeNavisLocale,
} from "./localization-core.mjs";
import { renderAdditionalSupport, renderAdditionalProductPage, PRODUCT_PAGES_STYLE, PRODUCT_PAGES_SCRIPT } from "./internal-product-pages.mjs";
import { renderProfilesPage, profilesPageScript } from "./profile-page.mjs";
import { renderNavisMark, NAVIS_BRAND_STYLE, NAVIS_BRAND_SCRIPT } from "./brand.mjs";

const MOZILLA_QUERY_STRIPPING_DOCUMENTATION =
  "https://firefox-source-docs.mozilla.org/toolkit/components/antitracking/anti-tracking/query-stripping/index.html";

const PROVIDER_ICON_PATHS = Object.freeze({
  add: "M10 4v12M4 10h12",
  edit: "m12.5 3.5 4 4M3 17l1-5L13 3a1.4 1.4 0 0 1 2 0l2 2a1.4 1.4 0 0 1 0 2l-9 9-5 1Z",
  remove: "M3 5h14M7 5V3h6v2M5 5l1 12h8l1-12M8 8v6M12 8v6",
  drag: "M7 4h.01M13 4h.01M7 10h.01M13 10h.01M7 16h.01M13 16h.01",
});

function providerIconMarkup(name) {
  return `<svg viewBox="0 0 20 20" aria-hidden="true" focusable="false"><path d="${PROVIDER_ICON_PATHS[name]}"/></svg>`;
}

const MATERIAL_INTERACTION_SCRIPT = String.raw`
(() => {
  "use strict";
  const selector = "button:not(:disabled), a[href], [role=button]:not([aria-disabled=true])";
  document.addEventListener("pointerdown", event => {
    if (!event.isTrusted || event.button !== 0) {
      return;
    }
    const target = event.composedPath().find(node => node?.matches?.(selector));
    if (!target) {
      return;
    }
    const bounds = target.getBoundingClientRect();
    const diameter = Math.ceil(Math.hypot(bounds.width, bounds.height) * 2);
    const ripple = document.createElement("span");
    ripple.className = "material-ripple";
    ripple.style.width = diameter + "px";
    ripple.style.height = diameter + "px";
    ripple.style.left = event.clientX - bounds.left - diameter / 2 + "px";
    ripple.style.top = event.clientY - bounds.top - diameter / 2 + "px";
    target.querySelector(":scope > .material-ripple")?.remove();
    target.append(ripple);
    ripple.addEventListener("animationend", () => ripple.remove(), { once: true });
  }, true);
})();`;

const APPEARANCE_SCRIPT = String.raw`
(() => {
  "use strict";
  window.addEventListener("NavisAppearanceState", event => {
    const appearance = event.detail?.appearance;
    if (!appearance || !["system", "light", "dark"].includes(appearance.theme) ||
        !/^#[0-9a-f]{6}$/i.test(appearance.accent)) return;
    const root = document.documentElement;
    root.dataset.theme = appearance.theme;
    root.style.colorScheme = appearance.theme === "system" ? "light dark" : appearance.theme;
    root.style.setProperty("--accent", appearance.accent);
    root.style.setProperty("--focus", appearance.accent);
  });
  window.addEventListener("pageshow", () => {
    document.dispatchEvent(new CustomEvent("NavisAppearanceCommand", {
      detail: { command: "appearance:get" }, bubbles: true,
    }));
  });
})();`;

function clientLocalizationScript(locale) {
  const normalized = normalizeNavisLocale(locale);
  const messages = JSON.stringify(NAVIS_CATALOGS[normalized]).replaceAll(
    "<",
    "\\u003c"
  );
  return `(() => {
    "use strict";
    const locale = ${JSON.stringify(normalized)};
    const messages = ${messages};
    const pluralRules = new Intl.PluralRules(locale);
    const numberFormat = new Intl.NumberFormat(locale);
    const text = (id, variables = {}) => {
      const record = messages[id];
      if (record === undefined) {
        throw new RangeError("Unknown Navis localization message: " + id);
      }
      const pattern = typeof record === "string"
        ? record
        : record[pluralRules.select(Number(variables.count))] || record.other;
      return pattern.replace(/\\{([A-Za-z][A-Za-z0-9]*)\\}/g, (_match, name) => {
        if (!Object.hasOwn(variables, name)) {
          throw new TypeError("Missing Navis localization variable: " + name);
        }
        return name === "count" && Number.isFinite(Number(variables[name]))
          ? numberFormat.format(Number(variables[name]))
          : String(variables[name]);
      });
    };
    window.NavisL10n = Object.freeze({
      locale,
      text,
      dateTime(value) {
        return new Intl.DateTimeFormat(locale, {
          dateStyle: "medium",
          timeStyle: "short",
        }).format(new Date(value));
      },
      number(value) {
        return numberFormat.format(value);
      },
    });
  })();`;
}

const SETTINGS_SCRIPT = String.raw`
(() => {
  "use strict";

  const search = document.getElementById("settings-search");
  const cards = [...document.querySelectorAll("[data-settings-card]")];
  const noResults = document.getElementById("settings-no-results");
  const status = document.getElementById("settings-status");
  const clearButton = document.getElementById("clear-site-data");
  const searchProvider = document.getElementById("search-provider");
  const remoteSuggestionsToggle = document.getElementById("remote-suggestions-toggle");
  const language = document.getElementById("display-language");
  const languageDetail = document.getElementById("display-language-detail");
  const languageRelaunch = document.getElementById(
    "display-language-relaunch"
  );
  const processIsolation = document.getElementById("process-isolation");
  const processIsolationChoices = [
    ...document.querySelectorAll('input[name="process-isolation"]'),
  ];
  const processIsolationDetail = document.getElementById(
    "process-isolation-detail"
  );
  const processIsolationRelaunch = document.getElementById(
    "process-isolation-relaunch"
  );
  const cleanLinksToggle = document.getElementById("clean-links-toggle");
  const sections = [...document.querySelectorAll("[data-settings-section]")];
  const routeLinks = [...document.querySelectorAll("[data-settings-route]")];
  const providerList = document.getElementById("search-provider-list");
  const providerDialog = document.getElementById("provider-dialog");
  const providerForm = document.getElementById("provider-form");
  const providerAdd = document.getElementById("provider-add");
  const theme = document.getElementById("appearance-theme");
  const accent = document.getElementById("appearance-accent");
  const accentReset = document.getElementById("appearance-accent-reset");
  const bookmarkBar = document.getElementById("bookmark-bar-mode");
  const directory = document.getElementById("download-directory");
  const chooseDirectory = document.getElementById("choose-download-directory");
  const settingToggles = [...document.querySelectorAll("[data-setting-key]")];
  let editingProviderId = null;
  let selectedSection = "search";
  const processIsolationLabelIds = Object.freeze({
    full: "settings.processIsolationFull",
    selective: "settings.processIsolationSelective",
    shared: "settings.processIsolationShared",
  });
  let busy = false;
  let settingsLoaded = false;
  let activeRequest = null;
  const pendingWrites = [];
  let accentDraft = null;
  let accentVersion = 0;
  let confirmedAccent = null;
  let defaultAccent = null;
  let requestTimer = null;
  let clearConfirmationTimer = null;
  let pageShown = false;

  const setStatus = (message, pending = false) => {
    if (!status) {
      return;
    }
    status.textContent = message;
    status.setAttribute("aria-label", message);
    status.toggleAttribute("data-pending", pending);
  };

  const renderControls = () => {
    const writing = activeRequest?.command !== "settings:get" && busy || pendingWrites.length > 0;
    const disabled = !settingsLoaded || writing;
    for (const control of [providerAdd, remoteSuggestionsToggle, theme, bookmarkBar, chooseDirectory, ...settingToggles]) {
      if (control) {
        control.disabled = disabled;
      }
    }
    accent.disabled = !settingsLoaded;
    accentReset.disabled = disabled || !defaultAccent ||
      (accentDraft?.value ?? confirmedAccent) === defaultAccent;
    for (const control of providerList?.querySelectorAll("button") || []) {
      control.disabled = disabled || control.dataset.unavailable === "true";
    }
    if (providerForm) {
      for (const control of providerForm.elements) {
        control.disabled = disabled;
      }
    }
    if (clearButton) {
      clearButton.disabled = disabled;
    }
    if (searchProvider) {
      searchProvider.disabled = disabled;
    }
    if (language) {
      language.disabled = disabled;
    }
    if (languageRelaunch) {
      languageRelaunch.disabled = disabled;
    }
    if (processIsolation) {
      processIsolation.disabled = disabled;
    }
    if (processIsolationRelaunch) {
      processIsolationRelaunch.disabled = disabled;
    }
    if (cleanLinksToggle) {
      cleanLinksToggle.disabled = disabled;
    }
  };

  const startRequest = request => {
    activeRequest = request;
    busy = true;
    renderControls();
    if (!settingsLoaded || request.command !== "settings:get") {
      setStatus(window.NavisL10n.text("settings.applying"), true);
    }
    clearTimeout(requestTimer);
    requestTimer = setTimeout(() => {
      activeRequest = null;
      busy = false;
      renderControls();
      setStatus(window.NavisL10n.text("settings.failed"));
      drainWrites();
    }, 10000);
    document.dispatchEvent(
      new CustomEvent("NavisSettingsCommand", {
        detail: { command: request.command, value: request.value },
        bubbles: true,
      })
    );
  };

  const drainWrites = () => {
    if (!busy && pendingWrites.length) {
      startRequest(pendingWrites.shift());
    }
  };

  const dispatchCommand = (command, value, draftVersion = null) => {
    const request = { command, value, draftVersion };
    if (busy) {
      if (command === "settings:get") return;
      const replace = command === "settings:set-appearance"
        ? pendingWrites.findIndex(item => item.command === command && item.value.key === value.key) : -1;
      if (replace < 0) pendingWrites.push(request);
      else pendingWrites[replace] = request;
      renderControls();
      return;
    }
    startRequest(request);
  };

  const filterSettings = () => {
      const query = search.value.trim().toLocaleLowerCase();
      let visible = 0;
      for (const section of sections) {
        section.hidden = !query && section.dataset.settingsSection !== selectedSection;
      }
      for (const card of cards) {
        const matches =
          !query ||
          ((card.dataset.search || "") + " " + card.textContent)
            .toLocaleLowerCase()
            .includes(query);
        card.hidden = !matches;
        card.setAttribute("aria-hidden", String(!matches));
        visible += Number(matches);
      }
      if (noResults) {
        noResults.hidden = !query || visible !== 0;
      }
  };
  search?.addEventListener("input", filterSettings);

  const showRoute = () => {
    const route = location.pathname.replace(/^\//, "").replace(/\/$/, "") || "search";
    selectedSection = sections.some(section => section.dataset.settingsSection === route) ? route : "search";
    for (const link of routeLinks) {
      const selected = link.dataset.settingsRoute === selectedSection;
      if (selected) {
        link.setAttribute("aria-current", "page");
        document.title = link.textContent.trim() + " — Navis";
      } else {
        link.removeAttribute("aria-current");
      }
    }
    document.documentElement.dataset.pageKey = "settings/" + selectedSection;
    filterSettings();
  };
  for (const link of routeLinks) {
    link.addEventListener("click", event => {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
        return;
      }
      event.preventDefault();
      try {
        if (location.href !== link.href) {
          history.pushState(null, "", link.href);
        }
      } catch {
        setStatus(window.NavisL10n.text("settings.failed"));
        return;
      }
      search.value = "";
      showRoute();
      window.scrollTo(0, 0);
      dispatchCommand("settings:get");
    });
  }
  document.querySelector(".settings-brand")?.addEventListener("click", event => {
    if (!event.ctrlKey && !event.metaKey && event.button === 0) {
      event.preventDefault();
      routeLinks[0].click();
    }
  });
  window.addEventListener("popstate", () => {
    search.value = "";
    showRoute();
    dispatchCommand("settings:get");
  });
  showRoute();

  const editProvider = provider => {
    editingProviderId = provider?.id || null;
    providerForm.elements.name.value = provider?.name || "";
    providerForm.elements.template.value = provider?.template || "";
    providerForm.elements.suggestionTemplate.value = provider?.suggestionTemplate || "";
    providerDialog.showModal();
    providerForm.elements.name.focus();
  };
  providerAdd?.addEventListener("click", () => editProvider(null));
  document.getElementById("provider-cancel")?.addEventListener("click", () => providerDialog.close());
  providerForm?.addEventListener("submit", event => {
    event.preventDefault();
    const value = {
      name: providerForm.elements.name.value,
      template: providerForm.elements.template.value,
      suggestionTemplate: providerForm.elements.suggestionTemplate.value,
    };
    if (editingProviderId) {
      value.id = editingProviderId;
    }
    dispatchCommand(editingProviderId ? "settings:search-update" : "settings:search-add", value);
  });
  const providerIconPaths = ${JSON.stringify(PROVIDER_ICON_PATHS)};
  let providerDrag = null;
  let providerPointer = null;
  let providerSnapshot = null;
  let providerFocusId = null;
  const providersBusy = () => busy || !settingsLoaded || pendingWrites.length > 0;
  const clearProviderIndicators = () => {
    for (const row of providerList.children) delete row.dataset.drop;
  };
  const clearProviderDrag = () => {
    providerDrag = null;
    providerPointer = null;
    clearProviderIndicators();
    for (const row of providerList.children) delete row.dataset.dragging;
  };
  const providerIconButton = (icon, label, handler) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "settings-provider-icon";
    button.title = label;
    button.setAttribute("aria-label", label);
    button.disabled = providersBusy();
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 20 20");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", providerIconPaths[icon]);
    svg.append(path);
    button.append(svg);
    if (handler) button.addEventListener("click", () => {
      if (!providersBusy()) handler();
    });
    return button;
  };
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && providerDrag) {
      event.preventDefault();
      clearProviderDrag();
    }
  });
  const finishProviderPointer = event => {
    // Native HTML dragging sends pointercancel before dragend/drop.
    if (!providerDrag && event.pointerId === providerPointer) providerPointer = null;
  };
  window.addEventListener("pointerup", finishProviderPointer, true);
  window.addEventListener("pointercancel", finishProviderPointer, true);
  window.addEventListener("blur", () => {
    if (!providerDrag) clearProviderDrag();
  });
  window.addEventListener("pagehide", clearProviderDrag);
  const renderProviders = providers => {
    const snapshot = JSON.stringify(providers);
    if (snapshot === providerSnapshot) return;
    providerSnapshot = snapshot;
    const focusedId = providerFocusId || providerList.querySelector(":focus")?.dataset.providerId;
    providerFocusId = null;
    clearProviderDrag();
    providerList.replaceChildren();
    const move = (id, position) => {
      if (providersBusy() || position < 0 || position >= providers.length ||
          providers[position].id === id) return;
      providerFocusId = id;
      clearProviderDrag();
      dispatchCommand("settings:search-move", { id, position });
    };
    providers.forEach((provider, index) => {
      const row = document.createElement("div");
      row.className = "settings-provider-row";
      row.setAttribute("role", "listitem");
      const handle = providerIconButton("drag", window.NavisL10n.text("settings.reorderProvider", { name: provider.name }));
      handle.classList.add("settings-provider-handle");
      handle.dataset.providerId = provider.id;
      handle.draggable = true;
      handle.setAttribute("aria-describedby", "provider-reorder-help");
      handle.setAttribute("aria-keyshortcuts", "ArrowUp ArrowDown");
      handle.addEventListener("pointerdown", event => {
        if (event.button === 0 && !providersBusy()) providerPointer = event.pointerId;
      });
      handle.addEventListener("keydown", event => {
        if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
        event.preventDefault();
        move(provider.id, index + (event.key === "ArrowUp" ? -1 : 1));
      });
      handle.addEventListener("dragstart", event => {
        if (providersBusy() || !event.dataTransfer) {
          event.preventDefault();
          return;
        }
        clearProviderDrag();
        providerDrag = { id: provider.id, index };
        event.dataTransfer.effectAllowed = "move";
        event.dataTransfer.setData("text/plain", provider.id);
        row.dataset.dragging = "true";
      });
      handle.addEventListener("dragend", clearProviderDrag);
      row.addEventListener("dragover", event => {
        if (!providerDrag || providersBusy()) {
          clearProviderIndicators();
          return;
        }
        event.preventDefault();
        const bounds = row.getBoundingClientRect();
        const side = event.clientY < bounds.top + bounds.height / 2 ? "before" : "after";
        clearProviderIndicators();
        row.dataset.drop = side;
        if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
      });
      row.addEventListener("dragleave", event => {
        if (!row.contains(event.relatedTarget)) delete row.dataset.drop;
      });
      row.addEventListener("drop", event => {
        if (!providerDrag) return;
        event.preventDefault();
        const source = providerDrag;
        const bounds = row.getBoundingClientRect();
        const insertion = index + Number(event.clientY >= bounds.top + bounds.height / 2);
        clearProviderDrag();
        move(source.id, insertion - Number(source.index < insertion));
      });
      const copy = document.createElement("div");
      copy.className = "settings-provider-copy";
      const name = document.createElement("strong");
      name.textContent = provider.name;
      const url = document.createElement("small");
      url.textContent = provider.template;
      copy.append(name, url);
      row.append(handle, copy);
      const actions = document.createElement("div");
      actions.className = "settings-provider-actions";
      actions.append(providerIconButton("edit", window.NavisL10n.text("settings.editNamedProvider", { name: provider.name }), () => editProvider(provider)));
      if (!provider.builtIn) {
        actions.append(providerIconButton("remove", window.NavisL10n.text("settings.removeNamedProvider", { name: provider.name }),
          () => dispatchCommand("settings:search-remove", { id: provider.id })));
      }
      row.append(actions);
      providerList.append(row);
      if (provider.id === focusedId) handle.focus({ preventScroll: true });
    });
  };
  for (const choice of document.querySelectorAll('input[name="appearance-theme"]')) {
    choice.addEventListener("change", () => {
      if (choice.checked) {
        dispatchCommand("settings:set-appearance", { key: "theme", value: choice.value });
      }
    });
  }
  const captureAccent = () => {
    const value = accent.value.toLowerCase();
    const writingAccent = [activeRequest, ...pendingWrites].some(request =>
      request?.command === "settings:set-appearance" && request.value.key === "accent");
    if (value === confirmedAccent && !writingAccent) {
      accentDraft = null;
    } else if (accentDraft?.value !== value) {
      accentDraft = { value, version: ++accentVersion };
    }
    renderControls();
  };
  const commitAccent = (force = false) => {
    captureAccent();
    if (force && !accentDraft) {
      accentDraft = { value: accent.value.toLowerCase(), version: ++accentVersion };
    }
    if (accentDraft) {
      dispatchCommand("settings:set-appearance", { key: "accent", value: accentDraft.value }, accentDraft.version);
    }
  };
  accent.addEventListener("input", captureAccent);
  accent.addEventListener("change", () => commitAccent());
  accentReset.addEventListener("click", () => {
    if (accentReset.disabled || !defaultAccent) return;
    accent.value = defaultAccent;
    commitAccent(true);
  });
  bookmarkBar?.addEventListener("change", () => dispatchCommand("settings:set-appearance", { key: "bookmarkBar", value: bookmarkBar.value }));
  chooseDirectory?.addEventListener("click", () => dispatchCommand("settings:choose-download-directory"));
  for (const toggle of settingToggles) {
    toggle.addEventListener("click", () => dispatchCommand("settings:set-" + toggle.dataset.settingGroup, {
      key: toggle.dataset.settingKey,
      value: toggle.getAttribute("aria-checked") !== "true",
    }));
  }

  searchProvider?.addEventListener("change", () => {
    dispatchCommand("settings:set-search-provider", searchProvider.value);
  });

  language?.addEventListener("change", () => {
    dispatchCommand("settings:set-locale", language.value);
  });

  languageRelaunch?.addEventListener("click", () => {
    dispatchCommand("settings:relaunch-locale");
  });

  for (const choice of processIsolationChoices) {
    choice.addEventListener("change", () => {
      if (choice.checked) {
        dispatchCommand("settings:set-process-isolation", choice.value);
      }
    });
  }

  processIsolationRelaunch?.addEventListener("click", () => {
    dispatchCommand("settings:relaunch-process-isolation");
  });

  cleanLinksToggle?.addEventListener("click", () => {
    dispatchCommand(
      "settings:set-clean-links",
      cleanLinksToggle.getAttribute("aria-checked") !== "true"
    );
  });

  remoteSuggestionsToggle?.addEventListener("click", () => {
    if (!remoteSuggestionsToggle.disabled) {
      dispatchCommand("settings:set-remote-suggestions",
        remoteSuggestionsToggle.getAttribute("aria-checked") !== "true");
    }
  });

  clearButton?.addEventListener("click", () => {
    if (clearButton.dataset.confirm !== "true") {
      clearButton.dataset.confirm = "true";
      clearButton.textContent = window.NavisL10n.text(
        "settings.confirmClearSiteData"
      );
      clearTimeout(clearConfirmationTimer);
      clearConfirmationTimer = setTimeout(() => {
        delete clearButton.dataset.confirm;
        clearButton.textContent = window.NavisL10n.text(
          "settings.clearSiteData"
        );
      }, 5000);
      return;
    }
    clearTimeout(clearConfirmationTimer);
    delete clearButton.dataset.confirm;
    clearButton.textContent = window.NavisL10n.text("settings.clearSiteData");
    dispatchCommand("settings:clear-site-data");
  });

  window.addEventListener("NavisSettingsState", event => {
    const state = event.detail;
    if (!state || !(state.pageKey === "settings" || state.pageKey?.startsWith("settings/"))) {
      return;
    }
    if (!activeRequest) return;
    const completedRequest = activeRequest;
    activeRequest = null;
    clearTimeout(requestTimer);
    busy = false;
    if (state.outcome === "failed") {
      renderControls();
      setStatus(window.NavisL10n.text("settings.failed"));
      drainWrites();
      return;
    }
    settingsLoaded = true;
    if (remoteSuggestionsToggle && typeof state.search?.remoteSuggestionsEnabled === "boolean") {
      remoteSuggestionsToggle.setAttribute("aria-checked", String(state.search.remoteSuggestionsEnabled));
    }
    if (searchProvider && state.search?.providers) {
      renderProviders(state.search.providers);
      const selected = state.search.defaultProvider;
      searchProvider.replaceChildren();
      for (const provider of state.search.providers) {
        const option = document.createElement("option");
        option.value = provider.id;
        option.textContent = provider.name;
        option.selected = provider.id === selected;
        searchProvider.append(option);
      }
    }
    if (state.appearance) {
      const appearance = state.appearance;
      for (const choice of document.querySelectorAll('input[name="appearance-theme"]')) {
        choice.checked = choice.value === appearance.theme;
      }
      confirmedAccent = appearance.accent;
      defaultAccent = appearance.defaultAccent;
      if (accentDraft?.version === completedRequest.draftVersion &&
          completedRequest.value?.value === confirmedAccent) {
        accentDraft = null;
      }
      accent.value = accentDraft?.value ?? confirmedAccent;
      bookmarkBar.value = appearance.bookmarkBar;
      document.documentElement.dataset.theme = appearance.theme;
      document.documentElement.style.colorScheme = appearance.theme === "system" ? "light dark" : appearance.theme;
      document.documentElement.style.setProperty("--accent", appearance.accent);
      document.documentElement.style.setProperty("--focus", appearance.accent);
    }
    if (directory && state.downloads) {
      directory.textContent = state.downloads.directory;
    }
    for (const toggle of settingToggles) {
      const source = toggle.dataset.settingGroup === "appearance" ? state.appearance : state.downloads;
      toggle.setAttribute("aria-checked", String(Boolean(source?.[toggle.dataset.settingKey])));
    }
    if (language && state.locale) {
      language.value = state.locale.selected;
      const activeOption = language.querySelector(
        'option[value="' + CSS.escape(state.locale.active) + '"]'
      );
      if (languageDetail) {
        languageDetail.textContent = state.locale.restartRequired
          ? window.NavisL10n.text("settings.languageRestart")
          : window.NavisL10n.text("settings.languageActive", {
              language: activeOption?.textContent || state.locale.active,
            });
      }
      if (languageRelaunch) {
        languageRelaunch.hidden = !state.locale.restartRequired;
      }
    }
    if (processIsolation && state.processIsolation) {
      for (const choice of processIsolationChoices) {
        choice.checked = choice.value === state.processIsolation.selected;
      }
      const activeLabelId =
        processIsolationLabelIds[state.processIsolation.active];
      if (processIsolationDetail) {
        processIsolationDetail.textContent = state.processIsolation.restartRequired
          ? window.NavisL10n.text("settings.processIsolationRestart")
          : window.NavisL10n.text("settings.processIsolationActive", {
              mode: activeLabelId
                ? window.NavisL10n.text(activeLabelId)
                : state.processIsolation.active,
            });
      }
      if (processIsolationRelaunch) {
        processIsolationRelaunch.hidden =
          !state.processIsolation.restartRequired;
      }
    }
    if (cleanLinksToggle && typeof state.cleanLinksEnabled === "boolean") {
      cleanLinksToggle.setAttribute(
        "aria-checked",
        String(state.cleanLinksEnabled)
      );
    }
    renderControls();
    if (state.outcome === "site-data-cleared") {
      setStatus(window.NavisL10n.text("settings.siteDataCleared"));
    } else if (state.outcome === "search-provider-updated") {
      providerDialog.close();
      setStatus(window.NavisL10n.text("settings.searchUpdated"));
    } else if (state.outcome === "appearance-updated" || state.outcome === "downloads-updated") {
      setStatus(window.NavisL10n.text("settings.updated"));
    } else if (state.outcome === "locale-updated") {
      setStatus(window.NavisL10n.text("settings.languageUpdated"));
    } else if (state.outcome === "locale-relaunching") {
      setStatus(window.NavisL10n.text("settings.languageRelaunching"), true);
    } else if (state.outcome === "locale-relaunch-cancelled") {
      setStatus(window.NavisL10n.text("settings.languageRelaunchCancelled"));
    } else if (state.outcome === "process-isolation-updated") {
      setStatus(window.NavisL10n.text("settings.processIsolationUpdated"));
    } else if (state.outcome === "process-isolation-relaunching") {
      setStatus(
        window.NavisL10n.text("settings.processIsolationRelaunching"),
        true
      );
    } else if (state.outcome === "process-isolation-relaunch-cancelled") {
      setStatus(
        window.NavisL10n.text("settings.processIsolationRelaunchCancelled")
      );
    } else if (state.outcome === "clean-links-updated") {
      setStatus(window.NavisL10n.text("settings.cleanLinksUpdated"));
    } else {
      setStatus(window.NavisL10n.text("settings.ready"));
    }
    drainWrites();
  });

  window.addEventListener("focus", () => {
    if (pageShown && !busy && providerPointer === null && !providerDrag) {
      dispatchCommand("settings:get");
    }
  });

  window.addEventListener(
    "pageshow",
    () => {
      pageShown = true;
      dispatchCommand("settings:get");
    },
    { once: true }
  );
})();`;

const MANAGEMENT_SCRIPT = String.raw`
(() => {
  "use strict";

  const pageKey = document.documentElement.dataset.pageKey;
  const list = document.getElementById("management-list");
  const status = document.getElementById("management-status");
  const search = document.getElementById("management-search");
  const clear = document.getElementById("management-clear");
  const addForm = document.getElementById("bookmark-add-form");
  const bookmarkBack = document.getElementById("bookmark-back");
  const homeForm = document.getElementById("newtab-search-form");
  let busy = false;
  let currentParent = "root";
  const folderStack = [];
  let clearConfirmationTimer = null;
  const downloadRows = new Map();
  const acknowledgedDownloads = new Map();
  let downloadSnapshot = null;
  let downloadRevision = -1;
  let downloadRenderFrame = null;
  let downloadAcknowledgeFrame = null;
  let downloadCommandFailed = false;
  let downloadVisibilityObserver = null;
  const downloadCommandQueue = [];
  const pendingDownloadOpens = new Set();
  let downloadActiveCommand = null;

  const setStatus = (message, pending = false) => {
    if (!status) {
      return;
    }
    status.textContent = message;
    status.setAttribute("aria-label", message);
    status.toggleAttribute("data-pending", pending);
  };

  const dispatch = (command, value, quiet = false) => {
    if (busy) {
      if (pageKey === "downloads" && ["downloads:cancel", "downloads:retry", "downloads:remove", "downloads:open"].includes(command)) {
        if (!downloadCommandQueue.some(item => item.command === command && item.value === value)) {
          downloadCommandQueue.push({ command, value });
        }
        return true;
      }
      return false;
    }
    busy = true;
    if (pageKey === "downloads") downloadActiveCommand = { command, value };
    if (!quiet) {
      setStatus(window.NavisL10n.text("management.loading"), true);
    }
    document.dispatchEvent(
      new CustomEvent("NavisManagementCommand", {
        detail: value === undefined ? { command } : { command, value },
        bubbles: true,
      })
    );
    return true;
  };

  const action = (label, handler, disabled = false) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "management-action";
    button.textContent = label;
    button.disabled = disabled;
    button.addEventListener("click", handler);
    return button;
  };

  const details = (titleText, detailText) => {
    const copy = document.createElement("div");
    copy.className = "management-row-copy";
    const title = document.createElement("strong");
    title.textContent = titleText;
    const detail = document.createElement("small");
    detail.textContent = detailText;
    copy.append(title, detail);
    return copy;
  };

  const row = item => {
    const element = document.createElement("article");
    element.className = "management-row";
    element.dataset.id = item.id || item.url || "";
    return element;
  };

  const renderHistory = state => {
    const fragment = document.createDocumentFragment();
    for (const item of state.items || []) {
      const element = row(item);
      const link = document.createElement("a");
      link.className = "management-row-link";
      link.href = item.url;
      link.append(
        details(
          item.title || item.url,
          window.NavisL10n.text("management.historyDate", {
            date: window.NavisL10n.dateTime(item.lastVisitedAt),
            url: item.url,
          })
        )
      );
      const actions = document.createElement("div");
      actions.className = "management-row-actions";
      actions.append(
        action(
          window.NavisL10n.text("common.delete"),
          () => dispatch("history:delete", item.url)
        )
      );
      element.append(link, actions);
      fragment.append(element);
    }
    list.replaceChildren(fragment);
    setStatus(
      state.private
        ? window.NavisL10n.text("history.privateUnavailable")
        : state.items?.length
          ? window.NavisL10n.text("history.entryCount", {
              count: state.items.length,
            })
          : window.NavisL10n.text("history.none")
    );
  };

  const renderBookmarks = state => {
    currentParent = state.parentId || currentParent;
    bookmarkBack.disabled = folderStack.length === 0;
    const fragment = document.createDocumentFragment();
    for (const item of state.items || []) {
      const element = row(item);
      let primary;
      if (item.type === "folder") {
        primary = document.createElement("button");
        primary.type = "button";
        primary.className = "management-row-link management-folder";
        primary.append(
          details(
            item.title || window.NavisL10n.text("common.untitledFolder"),
            window.NavisL10n.text("common.folder")
          )
        );
        primary.addEventListener("click", () => {
          folderStack.push(currentParent);
          dispatch("bookmarks:get", item.id);
        });
      } else {
        primary = document.createElement("a");
        primary.className = "management-row-link";
        primary.href = item.url;
        primary.append(details(item.title || item.url, item.url));
      }
      const actions = document.createElement("div");
      actions.className = "management-row-actions";
      actions.append(
        action(
          window.NavisL10n.text("common.delete"),
          () => dispatch("bookmarks:delete", item.id)
        )
      );
      element.append(primary, actions);
      fragment.append(element);
    }
    list.replaceChildren(fragment);
    setStatus(
      state.items?.length
        ? window.NavisL10n.text("bookmarks.itemCount", {
            count: state.items.length,
          })
        : window.NavisL10n.text("bookmarks.folderEmpty")
    );
  };

  const renderPasswords = state => {
    const fragment = document.createDocumentFragment();
    for (const item of state.items || []) {
      const element = row(item);
      element.append(
        details(
          item.username || window.NavisL10n.text("common.noUsername"),
          item.origin
        )
      );
      const actions = document.createElement("div");
      actions.className = "management-row-actions";
      actions.append(
        action(
          window.NavisL10n.text("common.show"),
          () => dispatch("passwords:reveal", item.id)
        ),
        action(
          window.NavisL10n.text("common.delete"),
          () => dispatch("passwords:delete", item.id)
        )
      );
      element.append(actions);
      fragment.append(element);
    }
    list.replaceChildren(fragment);
    setStatus(
      state.private
        ? window.NavisL10n.text("passwords.privateUnavailable")
        : state.items?.length
          ? window.NavisL10n.text("passwords.count", {
              count: state.items.length,
            })
          : window.NavisL10n.text("passwords.none")
    );
  };

  const renderRevealedPassword = credential => {
    const element = [...list.children].find(
      candidate => candidate.dataset.id === credential?.id
    );
    if (!element) {
      setStatus(window.NavisL10n.text("passwords.noLongerAvailable"));
      return;
    }
    element.querySelector("code")?.remove();
    const secret = document.createElement("code");
    secret.className = "management-secret";
    secret.textContent = credential.password;
    secret.setAttribute(
      "aria-label",
      window.NavisL10n.text("passwords.revealed")
    );
    element.querySelector(".management-row-copy")?.append(secret);
    setStatus(window.NavisL10n.text("passwords.authenticatedReveal"));
  };

  const downloadsVisible = () => document.visibilityState === "visible";

  const scheduleDownloadsAcknowledgement = () => {
    if (downloadAcknowledgeFrame !== null || !downloadsVisible() || !document.hasFocus()) {
      return;
    }
    // Run after the render frame, then recheck visibility/focus: merely fetching
    // downloads or painting a background tab must not clear the unread marker.
    downloadAcknowledgeFrame = requestAnimationFrame(() => {
      downloadAcknowledgeFrame = null;
      if (!downloadsVisible() || !document.hasFocus()) return;
      const receipts = [...downloadRows.values()]
        .filter(record => record.visible && record.item.status === "complete" &&
          typeof record.item.attentionToken === "string" && record.item.attentionToken &&
          acknowledgedDownloads.get(record.item.id) !== record.item.attentionToken)
        .map(record => ({ id: record.item.id, token: record.item.attentionToken }));
      if (!receipts.length) return;
      for (const { id, token } of receipts) acknowledgedDownloads.set(id, token);
      document.dispatchEvent(new CustomEvent("NavisManagementCommand", {
        detail: { command: "downloads:acknowledge", value: receipts }, bubbles: true,
      }));
    });
  };

  const createDownloadRow = item => {
    const element = row(item);
    element.classList.add("management-download-row");
    const copy = document.createElement("div");
    copy.className = "management-row-copy management-download-copy";
    const title = document.createElement("strong");
    const detail = document.createElement("small");
    const progress = document.createElement("div");
    progress.className = "management-download-progress";
    progress.setAttribute("role", "progressbar");
    progress.setAttribute("aria-valuemin", "0");
    progress.setAttribute("aria-valuemax", "100");
    const fill = document.createElement("span");
    progress.append(fill);
    copy.append(title, detail, progress);
    const actions = document.createElement("div");
    actions.className = "management-row-actions";
    const record = { element, title, detail, progress, fill, item, visible: false, buttons: {} };
    for (const [name, label] of [["cancel", "common.cancel"], ["retry", "common.retry"], ["remove", "common.remove"], ["open", "common.open"]]) {
      const button = action(window.NavisL10n.text(label), event => {
        if (button.hidden || button.disabled) return;
        if (name === "open" && event.detail > 1) return;
        if (name === "open") {
          pendingDownloadOpens.add(record.item.id);
          button.disabled = true;
        }
        dispatch("downloads:" + name, record.item.id);
      });
      record.buttons[name] = button;
      actions.append(button);
    }
    element.append(copy, actions);
    downloadVisibilityObserver?.observe(element);
    return record;
  };

  const renderDownloads = state => {
    const retained = new Set();
    let cursor = list.firstElementChild;
    for (const item of state.items || []) {
      if (!item.id || retained.has(item.id)) continue;
      retained.add(item.id);
      let record = downloadRows.get(item.id);
      if (!record) {
        record = createDownloadRow(item);
        downloadRows.set(item.id, record);
      }
      record.item = item;
      if (item.status !== "complete") acknowledgedDownloads.delete(item.id);
      // Stable progress ticks never detach rows/buttons or disturb focus.
      if (record.element !== cursor) list.insertBefore(record.element, cursor);
      cursor = record.element.nextElementSibling;
      const currentBytes = Math.max(0, Number(item.currentBytes) || 0);
      const totalBytes = Number(item.totalBytes);
      const hasTotal = Number.isFinite(totalBytes) && totalBytes > 0;
      const received = hasTotal
        ? window.NavisL10n.text("management.downloadBytes", {
            current: window.NavisL10n.number(currentBytes),
            total: window.NavisL10n.number(totalBytes),
          })
        : window.NavisL10n.text("management.downloadBytesUnknown", {
            current: window.NavisL10n.number(currentBytes),
          });
      const statusId =
        {
          pending: "downloads.starting",
          downloading: "common.loading",
          complete: "downloads.complete",
          canceled: "downloads.canceled",
          failed: "downloads.failed",
        }[item.status] || "downloads.unknown";
      const active = item.status === "pending" || item.status === "downloading";
      const determinate = hasTotal && item.status !== "pending";
      const percentage = Math.round(Math.max(0, Math.min(100,
        typeof item.progress === "number" && Number.isFinite(item.progress)
          ? item.progress : currentBytes / totalBytes * 100)));
      const progressText = determinate
        ? window.NavisL10n.text("downloads.progressSuffix", { progress: window.NavisL10n.number(percentage) }) : "";
      if (record.title.textContent !== item.fileName) record.title.textContent = item.fileName;
      record.title.title = item.fileName;
      const detailText = window.NavisL10n.text("management.downloadDetail", {
        status: window.NavisL10n.text(statusId), received: received + progressText,
      });
      if (record.detail.textContent !== detailText) record.detail.textContent = detailText;
      record.progress.hidden = !active;
      record.progress.setAttribute("aria-label", window.NavisL10n.text("downloads.progress", { name: item.fileName }));
      record.progress.setAttribute("aria-valuetext", record.detail.textContent);
      record.progress.toggleAttribute("data-indeterminate", !determinate);
      if (determinate) record.progress.setAttribute("aria-valuenow", String(percentage));
      else record.progress.removeAttribute("aria-valuenow");
      record.fill.style.transform = determinate ? "scaleX(" + percentage / 100 + ")" : "";
      record.buttons.cancel.hidden = !item.canCancel;
      record.buttons.retry.hidden = !item.canRetry;
      record.buttons.remove.hidden = active;
      record.buttons.open.hidden = item.status !== "complete";
      record.buttons.open.disabled = !item.canOpen || pendingDownloadOpens.has(item.id);
      for (const [name, button] of Object.entries(record.buttons)) {
        button.setAttribute("aria-label", window.NavisL10n.text("downloads." + name, { name: item.fileName }));
      }
    }
    for (const [id, record] of downloadRows) {
      if (retained.has(id)) continue;
      downloadVisibilityObserver?.unobserve(record.element);
      record.element.remove();
      downloadRows.delete(id);
      acknowledgedDownloads.delete(id);
    }
    if (!busy && !downloadCommandFailed) setStatus(
      state.items?.length
        ? window.NavisL10n.text("management.downloadsWindow", {
            count: state.items.length,
          })
        : window.NavisL10n.text(
            state.private
              ? "management.noPrivateDownloads"
              : "management.noDownloads"
          )
    );
    scheduleDownloadsAcknowledgement();
  };

  const queueDownloadsRender = state => {
    if (state) {
      const revision = Number(state.revision);
      if (!Number.isFinite(revision) || revision >= downloadRevision) {
        if (Number.isFinite(revision)) downloadRevision = revision;
        downloadSnapshot = state;
      }
    }
    if (!downloadSnapshot || downloadRenderFrame !== null || !downloadsVisible()) return;
    downloadRenderFrame = requestAnimationFrame(() => {
      downloadRenderFrame = null;
      if (downloadsVisible()) renderDownloads(downloadSnapshot);
    });
  };

  const refresh = () => {
    if (pageKey === "history") {
      dispatch("history:get", search?.value || "");
    } else if (pageKey === "bookmarks") {
      dispatch("bookmarks:get", currentParent);
    } else if (pageKey === "passwords") {
      dispatch("passwords:get");
    } else if (pageKey === "downloads") {
      dispatch("downloads:get", undefined, downloadRows.size > 0);
    }
  };

  window.addEventListener("NavisManagementState", event => {
    const state = event.detail;
    if (!state || state.pageKey !== pageKey) {
      return;
    }
    if (state.outcome === "acknowledged") return;
    busy = false;
    if (pageKey === "downloads") {
      const completed = downloadActiveCommand;
      downloadActiveCommand = null;
      if (completed?.command === "downloads:open") {
        pendingDownloadOpens.delete(completed.value);
        const record = downloadRows.get(completed.value);
        if (record) record.buttons.open.disabled = !record.item.canOpen;
      }
      downloadCommandFailed = state.outcome === "failed";
      if (downloadCommandFailed) setStatus(
        completed?.command === "downloads:open"
          ? window.NavisL10n.text("downloads.openFailed", { name: downloadRows.get(completed.value)?.item.fileName || "" })
          : window.NavisL10n.text("management.operationFailed")
      );
      else if (Array.isArray(state.items)) queueDownloadsRender(state);
      const next = downloadCommandQueue.shift();
      if (next) dispatch(next.command, next.value);
      else if (state.outcome === "updated") refresh();
      return;
    }
    if (state.outcome === "failed") {
      setStatus(window.NavisL10n.text("management.operationFailed"));
      return;
    }
    if (state.outcome === "updated") {
      refresh();
      return;
    }
    if (state.outcome === "revealed") {
      renderRevealedPassword(state.credential);
      return;
    }
    if (pageKey === "history") {
      renderHistory(state);
    } else if (pageKey === "bookmarks") {
      renderBookmarks(state);
    } else if (pageKey === "passwords") {
      renderPasswords(state);
    }
  });

  if (pageKey === "downloads") {
    // Geometry is delivered asynchronously by the engine. Downloads below the
    // viewport remain unread until the user actually brings their rows on screen.
    downloadVisibilityObserver = new IntersectionObserver(entries => {
      for (const entry of entries) {
        const record = downloadRows.get(entry.target.dataset.id);
        if (record) record.visible = entry.isIntersecting && entry.intersectionRatio > 0;
      }
      scheduleDownloadsAcknowledgement();
    });
    window.addEventListener("NavisDownloadsChanged", event => {
      const state = event.detail;
      if (state?.pageKey === "downloads" && Array.isArray(state.items)) queueDownloadsRender(state);
    });
    document.addEventListener("visibilitychange", () => {
      if (downloadsVisible()) queueDownloadsRender();
      else acknowledgedDownloads.clear();
    });
    window.addEventListener("blur", () => acknowledgedDownloads.clear());
    window.addEventListener("pagehide", () => {
      if (downloadRenderFrame !== null) cancelAnimationFrame(downloadRenderFrame);
      if (downloadAcknowledgeFrame !== null) cancelAnimationFrame(downloadAcknowledgeFrame);
      downloadRenderFrame = null;
      downloadAcknowledgeFrame = null;
    });
  }

  homeForm?.addEventListener("submit", event => {
    event.preventDefault();
    const input = document.getElementById("newtab-search");
    if (input.value.trim()) {
      dispatch("newtab:navigate", input.value);
    }
  });
  search?.addEventListener("input", () => {
    if (!busy) {
      refresh();
    }
  });
  clear?.addEventListener("click", () => {
    if (clear.dataset.confirm !== "true") {
      clear.dataset.confirm = "true";
      clear.textContent =
        pageKey === "history"
          ? window.NavisL10n.text("history.confirmClear")
          : window.NavisL10n.text("passwords.confirmClear");
      clearTimeout(clearConfirmationTimer);
      clearConfirmationTimer = setTimeout(() => {
        delete clear.dataset.confirm;
        clear.textContent =
          pageKey === "history"
            ? window.NavisL10n.text("history.clear")
            : window.NavisL10n.text("passwords.clear");
      }, 5000);
      return;
    }
    clearTimeout(clearConfirmationTimer);
    delete clear.dataset.confirm;
    clear.textContent =
      pageKey === "history"
        ? window.NavisL10n.text("history.clear")
        : window.NavisL10n.text("passwords.clear");
    const command = pageKey === "history" ? "history:clear" : "passwords:clear";
    dispatch(command);
  });
  bookmarkBack?.addEventListener("click", () => {
    if (folderStack.length) {
      dispatch("bookmarks:get", folderStack.pop());
    }
  });
  addForm?.addEventListener("submit", event => {
    event.preventDefault();
    const data = new FormData(addForm);
    const type = data.get("type") === "folder" ? "folder" : "bookmark";
    dispatch("bookmarks:create", {
      parentId: currentParent,
      type,
      title: String(data.get("title") || ""),
      url: type === "bookmark" ? String(data.get("url") || "") : "",
    });
    addForm.reset();
  });

  window.addEventListener("focus", () => {
    if (pageKey === "downloads") queueDownloadsRender();
    if (!busy && pageKey !== "newtab") {
      refresh();
    }
  });
  window.addEventListener(
    "pageshow",
    () => {
      if (pageKey !== "newtab") {
        refresh();
      }
    },
    { once: true }
  );
})();`;

const EXTENSIONS_SCRIPT = String.raw`
(() => {
  "use strict";

  const installButton = document.getElementById("extension-install");
  const builtInList = document.getElementById("built-in-extensions");
  const userList = document.getElementById("user-extensions");
  const builtInEmpty = document.getElementById("built-in-extensions-empty");
  const userEmpty = document.getElementById("user-extensions-empty");
  const status = document.getElementById("extension-status");
  const review = document.getElementById("extension-review");
  const reviewMark = document.getElementById("extension-review-mark");
  const reviewSummary = document.getElementById("extension-review-summary");
  const reviewDetails = document.getElementById("extension-review-details");
  const reviewCompatibility = document.getElementById(
    "extension-review-compatibility"
  );
  const reviewConfirm = document.getElementById("extension-review-confirm");
  const reviewCancel = document.getElementById("extension-review-cancel");
  const removalTimers = new Map();
  const errorMessages = new Map([
    ["runtime-unavailable", "extensions.error.runtime-unavailable"],
    ["signature-required", "extensions.error.signature-required"],
    ["invalid-package", "extensions.error.invalid-package"],
    ["incompatible-version", "extensions.error.incompatible-version"],
    ["unsupported-package-type", "extensions.error.unsupported-package-type"],
    ["unsupported-capability", "extensions.error.unsupported-capability"],
    ["immutable-built-in", "extensions.error.immutable-built-in"],
    ["downgrade-rejected", "extensions.error.downgrade-rejected"],
    ["same-version", "extensions.error.same-version"],
    ["preview-expired", "extensions.error.preview-expired"],
    ["private-access-unavailable", "extensions.error.private-access-unavailable"],
    ["action-unavailable", "extensions.error.action-unavailable"],
  ]);
  let busy = false;
  let pendingToken = "";
  let quietRefreshPending = false;

  const text = (id, variables) => window.NavisL10n.text(id, variables);

  const setStatus = (message, pending = false) => {
    status.textContent = message;
    status.setAttribute("aria-label", message);
    status.toggleAttribute("data-pending", pending);
  };

  const setBusy = (next, messageId = "extensions.applying") => {
    busy = next;
    installButton.disabled = next;
    document.getElementById("extension-list").toggleAttribute("aria-busy", next);
    for (const button of document.querySelectorAll(
      ".extension-card button, .extension-review-card button"
    )) {
      button.disabled = next || button.dataset.permanentDisabled === "true";
    }
    if (next) {
      setStatus(text(messageId), true);
    }
  };

  const dispatch = (command, value, messageId, { quiet = false } = {}) => {
    if (busy) {
      return false;
    }
    quietRefreshPending = quiet;
    if (!quiet) {
      setBusy(true, messageId);
    }
    document.dispatchEvent(
      new CustomEvent("NavisManagementCommand", {
        detail: value === undefined ? { command } : { command, value },
        bubbles: true,
      })
    );
    return true;
  };

  const button = (label, handler, className = "management-action") => {
    const element = document.createElement("button");
    element.type = "button";
    element.className = className;
    element.textContent = label;
    element.addEventListener("click", handler);
    return element;
  };

  const badge = label => {
    const element = document.createElement("span");
    element.className = "extension-badge";
    element.textContent = label;
    return element;
  };

  const permissionList = values => {
    const list = document.createElement("ul");
    list.className = "extension-permission-list";
    for (const value of values) {
      const item = document.createElement("li");
      item.textContent = value;
      list.append(item);
    }
    return list;
  };

  const extensionCard = extension => {
    const card = document.createElement("article");
    card.className = "extension-card";
    card.dataset.id = extension.id;
    card.dataset.runtimeReady = String(extension.runtimeReady);
    card.dataset.runtimeState = extension.runtimeState || "unavailable";

    const mark = document.createElement("div");
    mark.className = "extension-mark";
    mark.setAttribute("aria-hidden", "true");
    const fallbackMark = () => {
      mark.replaceChildren();
      mark.textContent = (extension.name || "E")
        .trim()
        .slice(0, 1)
        .toUpperCase();
    };
    if (extension.icon) {
      const icon = document.createElement("img");
      icon.alt = "";
      icon.src = extension.icon;
      icon.addEventListener("error", fallbackMark, { once: true });
      mark.append(icon);
    } else {
      fallbackMark();
    }

    const copy = document.createElement("div");
    copy.className = "extension-card-copy";
    const heading = document.createElement("div");
    heading.className = "extension-card-heading";
    const title = document.createElement("h3");
    title.textContent = extension.name || extension.id;
    const badges = document.createElement("span");
    badges.className = "extension-badges";
    badges.append(
      badge(
        text(
          extension.blockedSideload
            ? "extensions.blockedBadge"
            : extension.builtIn
            ? "extensions.builtInBadge"
            : "extensions.localBadge"
        )
      )
    );
    if (extension.correctlySigned) {
      badges.append(badge(text("extensions.signedBadge")));
    }
    heading.append(title, badges);

    const meta = document.createElement("p");
    meta.className = "extension-meta";
    meta.textContent =
      text("extensions.version", { version: extension.version }) +
      " · " +
      text("extensions.identifier", { id: extension.id });
    const description = document.createElement("p");
    description.className = "extension-description";
    description.textContent = extension.blockedSideload
      ? text("extensions.blockedSideload")
      : extension.description || "";
    copy.append(heading, meta, description);

    const toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "settings-switch extension-toggle";
    toggle.setAttribute("role", "switch");
    toggle.setAttribute("aria-checked", String(extension.enabled));
    toggle.setAttribute(
      "aria-label",
      (extension.enabled
        ? text("extensions.enabled")
        : text("extensions.disabled")) +
        ": " +
        (extension.name || extension.id)
    );
    toggle.title = extension.enabled
      ? text("extensions.enabled")
      : text("extensions.disabled");
    toggle.disabled = !extension.canChangeEnabled;
    toggle.dataset.permanentDisabled = String(!extension.canChangeEnabled);
    const thumb = document.createElement("span");
    thumb.className = "settings-switch-thumb";
    toggle.append(thumb);
    toggle.addEventListener("click", () => {
      dispatch("extensions:set-enabled", {
        id: extension.id,
        enabled: !extension.enabled,
      });
    });

    const required = [
      ...(extension.requiredPermissions || []),
      ...(extension.requiredOrigins || []),
    ];
    const granted = [
      ...(extension.grantedOptionalPermissions || []),
      ...(extension.grantedOptionalOrigins || []),
    ];
    const permissions = document.createElement("details");
    permissions.className = "extension-permissions";
    const summary = document.createElement("summary");
    summary.textContent = required.length
      ? text("extensions.permissionCount", { count: required.length })
      : text("extensions.noRequiredPermissions");
    permissions.append(summary);
    if (required.length) {
      permissions.append(permissionList(required));
    }
    if (granted.length) {
      permissions.append(permissionList(granted));
    }

    const privateAccess = document.createElement("div");
    privateAccess.className = "extension-private-access";
    const privateLabel = document.createElement("span");
    privateLabel.textContent = extension.privateBrowsingAvailable
      ? text("extensions.allowPrivate")
      : text("extensions.privateUnavailable");
    const privateToggle = document.createElement("button");
    privateToggle.type = "button";
    privateToggle.className = "settings-switch";
    privateToggle.setAttribute("role", "switch");
    privateToggle.setAttribute(
      "aria-checked",
      String(extension.privateBrowsingAllowed)
    );
    privateToggle.setAttribute("aria-label", privateLabel.textContent);
    privateToggle.disabled = !extension.privateBrowsingAvailable;
    privateToggle.dataset.permanentDisabled = String(
      !extension.privateBrowsingAvailable
    );
    const privateThumb = document.createElement("span");
    privateThumb.className = "settings-switch-thumb";
    privateToggle.append(privateThumb);
    privateToggle.addEventListener("click", () => {
      dispatch("extensions:set-private-browsing", {
        id: extension.id,
        enabled: !extension.privateBrowsingAllowed,
      });
    });
    privateAccess.append(privateLabel, privateToggle);
    if (extension.builtIn) {
      privateAccess.hidden = true;
    }

    const toolbarAccess = document.createElement("div");
    toolbarAccess.className = "extension-private-access extension-toolbar-access";
    const toolbarLabel = document.createElement("span");
    toolbarLabel.textContent = extension.hasAction
      ? text("extensions.pinToToolbar")
      : text("extensions.actionUnavailable");
    const toolbarToggle = document.createElement("button");
    toolbarToggle.type = "button";
    toolbarToggle.className = "settings-switch";
    toolbarToggle.setAttribute("role", "switch");
    toolbarToggle.setAttribute(
      "aria-checked",
      String(extension.toolbarPinned)
    );
    toolbarToggle.setAttribute("aria-label", toolbarLabel.textContent);
    toolbarToggle.disabled = !extension.enabled || !extension.hasAction;
    toolbarToggle.dataset.permanentDisabled = String(
      !extension.enabled || !extension.hasAction
    );
    const toolbarThumb = document.createElement("span");
    toolbarThumb.className = "settings-switch-thumb";
    toolbarToggle.append(toolbarThumb);
    toolbarToggle.addEventListener("click", () => {
      dispatch("extensions:set-pinned", {
        id: extension.id,
        enabled: !extension.toolbarPinned,
      });
    });
    toolbarAccess.append(toolbarLabel, toolbarToggle);

    const actions = document.createElement("div");
    actions.className = "extension-actions";
    if (extension.hasOptions) {
      actions.append(
        button(text("extensions.options"), () => {
          dispatch("extensions:open-options", extension.id);
        })
      );
    }
    if (extension.canUpdate) {
      actions.append(
        button(text("extensions.updateFromFile"), () => {
          dispatch(
            "extensions:select-package",
            undefined,
            "extensions.choosingPackage"
          );
        })
      );
    }
    if (extension.canUninstall) {
      const remove = button(text("extensions.remove"), () => {
        if (remove.dataset.confirm === "true") {
          clearTimeout(removalTimers.get(extension.id));
          removalTimers.delete(extension.id);
          dispatch("extensions:uninstall", extension.id);
          return;
        }
        remove.dataset.confirm = "true";
        remove.classList.add("danger");
        remove.textContent = text("extensions.confirmRemove");
        removalTimers.set(
          extension.id,
          setTimeout(() => {
            delete remove.dataset.confirm;
            remove.classList.remove("danger");
            remove.textContent = text("extensions.remove");
            removalTimers.delete(extension.id);
          }, 5000)
        );
      });
      actions.append(remove);
    }

    const controls = document.createElement("div");
    controls.className = "extension-card-controls";
    controls.append(toggle);
    card.append(
      mark,
      copy,
      controls,
      permissions,
      privateAccess,
      toolbarAccess,
      actions
    );
    return card;
  };

  const renderExtensions = extensions => {
    for (const timer of removalTimers.values()) {
      clearTimeout(timer);
    }
    removalTimers.clear();
    const builtIns = extensions.filter(extension => extension.builtIn);
    const users = extensions.filter(extension => !extension.builtIn);
    builtInList.replaceChildren(...builtIns.map(extensionCard));
    userList.replaceChildren(...users.map(extensionCard));
    builtInEmpty.hidden = builtIns.length !== 0;
    userEmpty.hidden = users.length !== 0;
  };

  const reviewRows = (heading, values) => {
    if (!values.length) {
      return null;
    }
    const section = document.createElement("section");
    const title = document.createElement("h3");
    title.textContent = heading;
    section.append(title, permissionList(values));
    return section;
  };

  const showReview = preview => {
    pendingToken = preview.token;
    reviewMark.textContent = (preview.name || "E").trim().slice(0, 1).toUpperCase();
    document.getElementById("extension-review-title").textContent = preview.name;
    reviewSummary.textContent = preview.existingVersion
      ? text("extensions.reviewUpdate", {
          oldVersion: preview.existingVersion,
          newVersion: preview.version,
        })
      : text("extensions.reviewInstall");
    reviewDetails.replaceChildren();
    const identity = document.createElement("div");
    identity.className = "extension-review-identity";
    for (const value of [
      text("extensions.version", { version: preview.version }),
      text("extensions.identifier", { id: preview.id }),
      text("extensions.package", { name: preview.packageName }),
      text("extensions.packageSize", {
        bytes: window.NavisL10n.number(preview.packageBytes),
      }),
    ]) {
      const row = document.createElement("p");
      row.textContent = value;
      identity.append(row);
    }
    reviewDetails.append(identity);
    const required = [
      ...(preview.compatibility.requiredPermissions || []),
      ...(preview.compatibility.requiredOrigins || []),
    ];
    const requiredSection = reviewRows(
      text("extensions.requiredAccess"),
      required
    );
    if (requiredSection) {
      reviewDetails.append(requiredSection);
    }
    const unavailable = [
      ...(preview.compatibility.issues || []),
      ...(preview.compatibility.unsupportedOptionalPermissions || []),
    ];
    const unavailableSection = reviewRows(
      text("extensions.optionalUnsupported"),
      unavailable
    );
    if (unavailableSection) {
      reviewDetails.append(unavailableSection);
    }
    const compatible = Boolean(preview.compatibility.compatible);
    reviewCompatibility.textContent = text(
      compatible ? "extensions.compatible" : "extensions.incompatible"
    );
    reviewCompatibility.toggleAttribute("data-incompatible", !compatible);
    reviewConfirm.textContent = text(
      preview.existingVersion
        ? "extensions.confirmUpdate"
        : "extensions.confirmInstall"
    );
    reviewConfirm.disabled = !compatible;
    reviewConfirm.dataset.permanentDisabled = String(!compatible);
    if (!review.open) {
      review.showModal();
    }
  };

  const closeReview = () => {
    pendingToken = "";
    if (review.open) {
      review.close();
    }
  };

  const errorText = code =>
    text(
      errorMessages.get(code) || "extensions.error.operation-failed"
    );

  const outcomeMessage = outcome => {
    const key = new Map([
      ["installed", "extensions.installed"],
      ["updated", "extensions.updated"],
      ["state-updated", "extensions.stateUpdated"],
      ["private-access-updated", "extensions.privateAccessUpdated"],
      ["toolbar-updated", "extensions.toolbarUpdated"],
      ["uninstalled", "extensions.uninstalled"],
      ["options-opened", "extensions.optionsOpened"],
      ["cancelled", "extensions.cancelled"],
    ]).get(outcome);
    return text(key || "extensions.ready");
  };

  window.addEventListener("NavisManagementState", event => {
    const state = event.detail;
    if (!state || state.pageKey !== "extensions") {
      return;
    }
    const preserveStatus =
      quietRefreshPending && state.outcome === "ready";
    quietRefreshPending = false;
    if (Array.isArray(state.extensions)) {
      renderExtensions(state.extensions);
    }
    setBusy(false);
    if (state.outcome === "preview" && state.preview) {
      showReview(state.preview);
      setStatus(text("extensions.reviewTitle"));
      return;
    }
    if (state.outcome === "failed" || state.outcome === "rejected") {
      closeReview();
      setStatus(errorText(state.error?.code));
      return;
    }
    if (preserveStatus) {
      return;
    }
    closeReview();
    setStatus(outcomeMessage(state.outcome));
  });

  installButton.addEventListener("click", () => {
    dispatch(
      "extensions:select-package",
      undefined,
      "extensions.choosingPackage"
    );
  });
  reviewConfirm.addEventListener("click", () => {
    if (pendingToken) {
      dispatch("extensions:confirm-install", pendingToken);
    }
  });
  reviewCancel.addEventListener("click", () => {
    if (pendingToken) {
      dispatch("extensions:cancel-install", pendingToken);
    } else {
      closeReview();
    }
  });
  review.addEventListener("cancel", event => {
    event.preventDefault();
    if (pendingToken && !busy) {
      dispatch("extensions:cancel-install", pendingToken);
    }
  });
  window.addEventListener("focus", () => {
    if (!busy && !review.open) {
      // A focus refresh reconciles changes made in an options page, but it
      // must not replace the result of the user's preceding command with the
      // generic "ready" message.  It also stays non-blocking so a user action
      // can supersede an in-flight snapshot in the actor's request ordering.
      dispatch("extensions:get", undefined, undefined, { quiet: true });
    }
  });
  window.addEventListener(
    "pageshow",
    () => dispatch("extensions:get", undefined, "extensions.loading"),
    { once: true }
  );
})();`;

const DIAGNOSTICS_SCRIPT = String.raw`
(() => {
  "use strict";

  const pageKey = document.documentElement.dataset.pageKey;
  const settingsDocument = pageKey === "settings" || pageKey.startsWith("settings/");
  const rows = new Map(
    [...document.querySelectorAll("[data-diagnostic-label]")].map(row => [
      row.dataset.diagnosticLabel,
      row.querySelector("dd"),
    ])
  );
  const dynamicLabels = new Set(
    [...document.querySelectorAll("[data-diagnostic-dynamic]")].map(
      row => row.dataset.diagnosticLabel
    )
  );
  const copyButton = document.getElementById("copy-diagnostics");
  const copyStatus = document.getElementById("copy-diagnostics-status");
  const localizedDynamicValues = new Map([
    ["active", "diagnostics.active"],
    ["not-active", "diagnostics.notActive"],
    ["enabled", "diagnostics.enabled"],
    ["disabled", "diagnostics.disabled"],
    ["included", "diagnostics.included"],
    ["not-included", "diagnostics.notIncluded"],
    ["online", "diagnostics.online"],
    ["offline", "diagnostics.offline"],
    ["private", "diagnostics.private"],
    ["regular", "diagnostics.regular"],
    ["unavailable", "common.unavailable"],
  ]);
  let pending = false;
  let requestTimer = null;
  let retryCount = 0;
  let pageShown = false;

  const fail = () => {
    pending = false;
    document.documentElement.dataset.diagnosticsState = "failed";
    for (const value of rows.values()) {
      if (value?.textContent === window.NavisL10n.text("common.loading")) {
        value.textContent = window.NavisL10n.text("common.unavailable");
      }
    }
  };

  const request = () => {
    if (pending || retryCount >= 100) {
      if (retryCount >= 100) {
        fail();
      }
      return;
    }
    pending = true;
    retryCount++;
    clearTimeout(requestTimer);
    requestTimer = setTimeout(fail, 10000);
    document.dispatchEvent(
      new CustomEvent("NavisDiagnosticsCommand", {
        detail: { command: "diagnostics:get" },
        bubbles: true,
      })
    );
  };

  window.addEventListener("NavisDiagnosticsState", event => {
    const state = event.detail;
    if (!state || !(state.pageKey === pageKey || settingsDocument && (state.pageKey === "settings" || state.pageKey?.startsWith("settings/")))) {
      return;
    }
    clearTimeout(requestTimer);
    pending = false;
    if (state.outcome === "pending") {
      setTimeout(request, 100);
      return;
    }
    if (state.outcome !== "ready" || !Array.isArray(state.diagnostics)) {
      fail();
      return;
    }
    const updated = new Set();
    for (const entry of state.diagnostics) {
      if (
        !Array.isArray(entry) ||
        entry.length !== 2 ||
        typeof entry[0] !== "string" ||
        typeof entry[1] !== "string"
      ) {
        continue;
      }
      const value = rows.get(entry[0]);
      if (!value || !dynamicLabels.has(entry[0])) {
        continue;
      }
      const messageId = localizedDynamicValues.get(entry[1]);
      const diagnosticValue = messageId
        ? window.NavisL10n.text(messageId)
        : entry[1];
      value.textContent =
        diagnosticValue || window.NavisL10n.text("common.unavailable");
      updated.add(entry[0]);
    }
    if (updated.size === dynamicLabels.size) {
      document.documentElement.dataset.diagnosticsState = "ready";
      if (copyButton) {
        copyButton.disabled = false;
      }
    } else {
      fail();
    }
  });

  const diagnosticsText = () => {
    const lines = [
      document.title,
      window.NavisL10n.text("diagnostics.generatedAt") +
        ": " +
        new Date().toISOString(),
    ];
    for (const section of document.querySelectorAll(".diagnostics-card")) {
      const heading = section.querySelector("h2")?.textContent?.trim();
      if (heading) {
        lines.push("", "[" + heading + "]");
      }
      for (const row of section.querySelectorAll("[data-diagnostic-label]")) {
        const label = row.querySelector("dt")?.textContent?.trim();
        const value = row.querySelector("dd")?.textContent?.trim();
        if (label && value) {
          lines.push(label + ": " + value);
        }
      }
    }
    return lines.join("\n");
  };

  copyButton?.addEventListener("click", async () => {
    copyButton.disabled = true;
    try {
      const text = diagnosticsText();
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const source = document.createElement("textarea");
        source.className = "diagnostics-copy-source";
        source.value = text;
        document.body.append(source);
        source.select();
        const copied = document.execCommand("copy");
        source.remove();
        if (!copied) {
          throw new Error("Clipboard command was rejected");
        }
      }
      if (copyStatus) {
        copyStatus.textContent = window.NavisL10n.text(
          "diagnostics.copyComplete"
        );
      }
    } catch {
      if (copyStatus) {
        copyStatus.textContent = window.NavisL10n.text(
          "diagnostics.copyFailed"
        );
      }
    } finally {
      copyButton.disabled = false;
    }
  });

  window.addEventListener("focus", () => {
    if (pageShown && !settingsDocument) {
      request();
    }
  });
  window.addEventListener(
    "pageshow",
    () => {
      pageShown = true;
      if (!settingsDocument) {
        request();
      }
    },
    { once: true }
  );
  if (settingsDocument) {
    window.addEventListener("NavisSettingsState", event => {
      if (event.detail?.outcome !== "failed") {
        request();
      }
    });
  }
})();`;

function escapeHTML(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function pageList(pages, t) {
  return pages
    .map(
      page => `
        <li>
          <a href="${escapeHTML(page.url)}">${escapeHTML(page.url)}</a>
          <span>${escapeHTML(t(page.descriptionId))}</span>
        </li>`
    )
    .join("");
}

function diagnosticRows(entries, t) {
  return entries
    .map(([labelId, value]) => {
      const isRecord = value && typeof value === "object";
      let localizedValue = value;
      if (isRecord && value.messageId) {
        localizedValue = t(value.messageId, value.variables || {});
      } else if (value === undefined || value === null || value === "") {
        localizedValue = t("common.unavailable");
      }
      const dynamicAttribute =
        isRecord && value.dynamic ? " data-diagnostic-dynamic" : "";
      return `
        <div class="diagnostic-row" data-diagnostic-label="${escapeHTML(labelId)}"${dynamicAttribute}>
          <dt>${escapeHTML(t(labelId))}</dt>
          <dd>${escapeHTML(localizedValue)}</dd>
        </div>`;
    })
    .join("");
}

const SETTINGS_NAV_ICONS = Object.freeze({
  search: '<circle cx="8.5" cy="8.5" r="5.5"/><path d="m12.5 12.5 4 4"/>',
  appearance: '<path d="M10 3a7 7 0 1 0 7 7c0-2-2-1-3-1s-2-1-1-3c.6-1.3-.8-3-3-3Z"/><circle cx="6" cy="8" r=".7"/><circle cx="7" cy="12" r=".7"/>',
  privacy:
    '<path d="M10 2.8 16 5v4.6c0 3.8-2.4 6.4-6 7.8-3.6-1.4-6-4-6-7.8V5l6-2.2Z"/><path d="m7.3 10 1.8 1.8 3.7-4"/>',
  history:
    '<path d="M4.5 5.5H2v-2"/><path d="M3 5a7 7 0 1 1 0 8"/><path d="M10 6v4l3 2"/>',
  bookmarks:
    '<path d="m10 2.8 2.2 4.5 5 .7-3.6 3.5.9 5-4.5-2.4L5.5 16l.9-5L2.8 8l5-.7L10 2.8Z"/>',
  passwords:
    '<circle cx="7" cy="9" r="3.5"/><path d="M10 9h7M14 9v2M16.5 9v2"/>',
  downloads:
    '<path d="M10 3v9"/><path d="m6.5 8.5 3.5 3.5 3.5-3.5"/><path d="M4 16.5h12"/>',
  extensions:
    '<path d="M7.2 3.2h5.6v3h3v5.6h-3v3H7.2v-3h-3V6.2h3v-3Z"/><path d="M8.2 7.4h3.6v3.2H8.2z"/>',
  help: '<circle cx="10" cy="10" r="7"/><path d="M10 9v5"/><circle cx="10" cy="6.2" r=".7" fill="currentColor"/>',
});

function settingsNavIcon(iconName) {
  return `<svg class="settings-nav-icon" viewBox="0 0 20 20" aria-hidden="true" focusable="false">${SETTINGS_NAV_ICONS[iconName]}</svg>`;
}

function settingsNavigation(page, t) {
  const selected = page.route || "search";
  const sections = [
    ["search", "settings.searchEngine"],
    ["privacy", "settings.privacySecurity"],
    ["appearance", "settings.appearance"],
    ["downloads", "settings.downloadConfiguration"],
    ["help", "internal.help.title"],
  ];
  return `
    <nav class="settings-nav" aria-label="${escapeHTML(t("settings.sections"))}">
      ${sections.map(([route, title]) => `<a data-settings-route="${route}" href="navis://settings/${route}"${selected === route ? ' aria-current="page"' : ""}>${settingsNavIcon(route)}${escapeHTML(t(title))}</a>`).join("")}
    </nav>`;
}

function settingsToggle(key, title, description, t, group = "download") {
  return `<div class="settings-row"><div class="settings-row-copy"><h2>${escapeHTML(t(title))}</h2><p>${escapeHTML(t(description))}</p></div><button data-setting-group="${group}" data-setting-key="${key}" class="settings-switch" type="button" role="switch" aria-checked="false" aria-label="${escapeHTML(t(title))}" disabled><span class="settings-switch-thumb"></span></button></div>`;
}

function settingsRoot(t) {
  return `
    <section data-settings-section="search">
    <header class="settings-page-heading">
      <h1>${escapeHTML(t("settings.searchEngine"))}</h1>
    </header>
    <section class="settings-card" data-settings-card data-search="search engine provider google baidu bing duckduckgo omnibox">
      <div class="settings-row">
        <div class="settings-row-copy">
          <h2>${escapeHTML(t("settings.defaultSearchProvider"))}</h2>
          <p>${escapeHTML(t("settings.searchEngineDescription"))}</p>
        </div>
        <label class="settings-select-label">
          <span class="sr-only">${escapeHTML(t("settings.defaultSearchProvider"))}</span>
          <select id="search-provider" class="settings-select" aria-label="${escapeHTML(t("settings.defaultSearchProvider"))}" disabled></select>
        </label>
      </div>
    </section>
    <section class="settings-card" data-settings-card data-search="remote search suggestions provider privacy 远程 搜索 建议 隐私">
      <div class="settings-row">
        <div class="settings-row-copy"><h2>${escapeHTML(t("settings.remoteSuggestions"))}</h2><p id="remote-suggestions-help">${escapeHTML(t("settings.remoteSuggestionsDescription"))}</p></div>
        <button id="remote-suggestions-toggle" class="settings-switch" type="button" role="switch" aria-checked="false" aria-label="${escapeHTML(t("settings.remoteSuggestions"))}" aria-describedby="remote-suggestions-help" disabled><span class="settings-switch-thumb"></span></button>
      </div>
    </section>
    <section class="settings-card" data-settings-card>
      <header class="settings-provider-header"><h2>${escapeHTML(t("settings.manageProviders"))}</h2><button id="provider-add" class="settings-provider-icon" type="button" title="${escapeHTML(t("settings.addProvider"))}" aria-label="${escapeHTML(t("settings.addProvider"))}" disabled>${providerIconMarkup("add")}</button></header>
      <p class="settings-provider-description">${escapeHTML(t("settings.providerTemplateHelp", { searchTerms: "{searchTerms}" }))}</p>
      <p id="provider-reorder-help" class="sr-only">${escapeHTML(t("settings.reorderProviderHint"))}</p>
      <div id="search-provider-list" class="settings-provider-list" role="list" aria-label="${escapeHTML(t("settings.manageProviders"))}"></div>
    </section>
    <dialog id="provider-dialog" class="settings-dialog" aria-labelledby="provider-dialog-title">
      <form id="provider-form"><h2 id="provider-dialog-title">${escapeHTML(t("settings.editProvider"))}</h2>
        <label>${escapeHTML(t("settings.providerName"))}<input name="name" maxlength="80" required autocomplete="off"></label>
        <label>${escapeHTML(t("settings.providerTemplate"))}<input name="template" maxlength="4096" required autocomplete="off" placeholder="https://example.com/search?q={searchTerms}"></label>
        <p>${escapeHTML(t("settings.providerTemplateHelp", { searchTerms: "{searchTerms}" }))}</p>
        <label>${escapeHTML(t("settings.providerSuggestionTemplate"))}<input name="suggestionTemplate" maxlength="4096" autocomplete="off" aria-describedby="provider-suggestions-help" placeholder="https://example.com/suggest?q={searchTerms}"></label>
        <p id="provider-suggestions-help">${escapeHTML(t("settings.providerSuggestionTemplateHelp", { searchTerms: "{searchTerms}" }))}</p>
        <div class="settings-dialog-actions"><button id="provider-cancel" class="settings-action" type="button">${escapeHTML(t("common.cancel"))}</button><button class="settings-action" type="submit">${escapeHTML(t("common.save"))}</button></div>
      </form>
    </dialog>
    </section>
    <section data-settings-section="privacy" hidden>
    <header class="settings-page-heading"><h1>${escapeHTML(t("settings.privacySecurity"))}</h1><p>${escapeHTML(t("settings.capabilityDescription"))}</p></header>
    <section class="settings-card" data-settings-card data-search="site process isolation security memory full selective shared performance 站点 进程 隔离 安全 内存 性能">
      <div class="settings-row settings-row-stacked">
        <div class="settings-row-copy">
          <h2>${escapeHTML(t("settings.processIsolation"))}</h2>
          <p>${escapeHTML(t("settings.processIsolationDescription"))}</p>
          <p class="settings-feature-detail">${escapeHTML(t("settings.processIsolationInvariant"))}</p>
        </div>
        <fieldset id="process-isolation" class="settings-choice-group" disabled>
          <legend class="sr-only">${escapeHTML(t("settings.processIsolation"))}</legend>
          <label class="settings-choice">
            <input type="radio" name="process-isolation" value="full" checked>
            <span class="settings-choice-indicator" aria-hidden="true"></span>
            <span class="settings-choice-copy">
              <strong>${escapeHTML(t("settings.processIsolationFull"))}</strong>
              <small>${escapeHTML(t("settings.processIsolationFullDescription"))}</small>
            </span>
            <span class="settings-recommended">${escapeHTML(t("settings.recommended"))}</span>
          </label>
          <label class="settings-choice">
            <input type="radio" name="process-isolation" value="selective">
            <span class="settings-choice-indicator" aria-hidden="true"></span>
            <span class="settings-choice-copy">
              <strong>${escapeHTML(t("settings.processIsolationSelective"))}</strong>
              <small>${escapeHTML(t("settings.processIsolationSelectiveDescription"))}</small>
            </span>
          </label>
          <label class="settings-choice">
            <input type="radio" name="process-isolation" value="shared">
            <span class="settings-choice-indicator" aria-hidden="true"></span>
            <span class="settings-choice-copy">
              <strong>${escapeHTML(t("settings.processIsolationShared"))}</strong>
              <small>${escapeHTML(t("settings.processIsolationSharedDescription"))}</small>
            </span>
          </label>
        </fieldset>
        <div class="settings-relaunch-row">
          <p id="process-isolation-detail"></p>
          <button id="process-isolation-relaunch" class="settings-action" type="button" hidden disabled>${escapeHTML(t("settings.processIsolationRelaunch"))}</button>
        </div>
      </div>
    </section>
    <section class="settings-card" data-settings-card data-search="clean links tracking parameters privacy copy open url 清理链接 跟踪参数 隐私">
      <div class="settings-row">
        <div class="settings-row-copy">
          <h2>${escapeHTML(t("settings.cleanLinks"))}</h2>
          <p>${escapeHTML(t("settings.cleanLinksDescription"))}</p>
          <p class="settings-feature-detail">${escapeHTML(t("settings.cleanLinksScope"))}</p>
          <a class="settings-help-link" href="${MOZILLA_QUERY_STRIPPING_DOCUMENTATION}" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">${escapeHTML(t("settings.cleanLinksLearnMore"))}<span aria-hidden="true">↗</span></a>
        </div>
        <button id="clean-links-toggle" class="settings-switch" type="button" role="switch" aria-checked="false" aria-label="${escapeHTML(t("settings.cleanLinks"))}" disabled>
          <span class="settings-switch-thumb"></span>
        </button>
      </div>
    </section>
    <section class="settings-card" data-settings-card data-search="clear cookies site data privacy storage">
      <div class="settings-row settings-row-stacked">
        <div class="settings-row-copy">
          <h2>${escapeHTML(t("siteInfo.cookiesTitle"))}</h2>
          <p>${escapeHTML(t("settings.cookiesDescription"))}</p>
        </div>
        <button id="clear-site-data" class="settings-action" type="button" disabled>${escapeHTML(t("settings.clearSiteData"))}</button>
      </div>
    </section>
    </section>
    <section data-settings-section="appearance" hidden>
    <header class="settings-page-heading"><h1>${escapeHTML(t("settings.appearance"))}</h1></header>
    <section class="settings-card" data-settings-card>
      <div class="settings-row settings-row-stacked"><div class="settings-row-copy"><h2>${escapeHTML(t("settings.theme"))}</h2></div>
        <fieldset id="appearance-theme" class="settings-theme-choices" disabled><legend class="sr-only">${escapeHTML(t("settings.theme"))}</legend>
          ${["system", "light", "dark"].map(mode => `<label class="settings-theme-choice"><input type="radio" name="appearance-theme" value="${mode}"><span class="settings-theme-preview" data-preview="${mode}" aria-hidden="true"><i></i><b></b><em></em></span><span>${escapeHTML(t(`settings.theme.${mode}`))}</span></label>`).join("")}
        </fieldset>
      </div>
      <div class="settings-row"><label for="appearance-accent">${escapeHTML(t("settings.accentColor"))}</label><div class="settings-accent-controls"><input id="appearance-accent" type="color" value="#0b57d0" disabled><button id="appearance-accent-reset" class="settings-action" type="button" disabled>${escapeHTML(t("settings.restoreDefaultColor"))}</button></div></div>
    </section>
    <section class="settings-card" data-settings-card>
      <div class="settings-row"><label for="bookmark-bar-mode">${escapeHTML(t("settings.bookmarkBar"))}</label><select id="bookmark-bar-mode" class="settings-select" disabled><option value="newtab">${escapeHTML(t("settings.bookmarkBarNewtab"))}</option><option value="always">${escapeHTML(t("settings.bookmarkBarAlways"))}</option><option value="never">${escapeHTML(t("settings.bookmarkBarNever"))}</option></select></div>
      ${settingsToggle("historyButton", "settings.historyButton", "settings.historyButtonDescription", t, "appearance")}
    </section>
    <section class="settings-card" data-settings-card data-search="language locale english chinese 中文 语言">
      <div class="settings-row">
        <div class="settings-row-copy">
          <h2>${escapeHTML(t("settings.language"))}</h2>
          <p>${escapeHTML(t("settings.languageDescription"))}</p>
          <p id="display-language-detail"></p>
        </div>
        <div class="settings-language-controls">
          <label class="settings-select-label">
            <span class="sr-only">${escapeHTML(t("settings.language"))}</span>
            <select id="display-language" class="settings-select" aria-label="${escapeHTML(t("settings.language"))}" disabled>
              <option value="system">${escapeHTML(t("settings.languageSystem"))}</option>
              <option value="en-US">${escapeHTML(t("settings.languageEnglish"))}</option>
              <option value="zh-CN">${escapeHTML(t("settings.languageChinese"))}</option>
            </select>
          </label>
          <button id="display-language-relaunch" class="settings-action" type="button" hidden disabled>${escapeHTML(t("settings.languageRelaunch"))}</button>
        </div>
      </div>
    </section>
    </section>
    <section data-settings-section="downloads" hidden>
      <header class="settings-page-heading"><h1>${escapeHTML(t("settings.downloadConfiguration"))}</h1></header>
      <section class="settings-card" data-settings-card>
        <div class="settings-row"><div class="settings-row-copy"><h2>${escapeHTML(t("settings.downloadDirectory"))}</h2><p id="download-directory"></p></div><button id="choose-download-directory" class="settings-action" type="button" disabled>${escapeHTML(t("settings.change"))}</button></div>
        ${settingsToggle("askBeforeSaving", "settings.askBeforeSaving", "settings.askBeforeSavingDescription", t)}
        ${settingsToggle("deletePrivateOnExit", "settings.deletePrivateDownloads", "settings.deletePrivateDownloadsDescription", t)}
        ${settingsToggle("openWhenComplete", "settings.openDownloads", "settings.openDownloadsDescription", t)}
      </section>
    </section>`;
}

function settingsHelp(diagnostics, t) {
  const application = diagnostics.application.filter(([label]) =>
    new Set([
      "diagnostics.version",
      "diagnostics.buildId",
      "diagnostics.displayLanguage",
    ]).has(label)
  );
  const engine = diagnostics.engine.filter(([label]) =>
    new Set(["diagnostics.geckoVersion", "diagnostics.geckoBuildId"]).has(label)
  );
  return `
    <header class="settings-page-heading">
      <h1>${escapeHTML(t("internal.help.title"))}</h1>
      <p>${escapeHTML(t("settings.aboutDescription"))}</p>
    </header>
    <section class="settings-card about-card" data-settings-card data-search="about navis version build application">
      <div class="about-product">
        <div class="about-mark">${renderNavisMark("navis-about", { animated: true })}</div>
        <div>
          <h2>${escapeHTML(t("app.name"))}</h2>
          <p>${escapeHTML(diagnostics.application[1]?.[1] || t("common.unavailable"))}</p>
        </div>
      </div>
      <dl>${diagnosticRows(application, t)}</dl>
    </section>
    <section class="settings-card" data-settings-card data-search="gecko engine version build process">
      <h2>${escapeHTML(t("internal.engine"))}</h2>
      <dl>${diagnosticRows(engine, t)}</dl>
    </section>
    <section class="settings-card" data-settings-card data-search="help diagnostics internal pages system information">
      <h2>${escapeHTML(t("settings.localHelp"))}</h2>
      <a class="settings-link-row" href="navis://support/">
        <span><strong>${escapeHTML(t("settings.systemDiagnostics"))}</strong><small>${escapeHTML(t("settings.systemDiagnosticsDescription"))}</small></span>
        <span aria-hidden="true">›</span>
      </a>
      <a class="settings-link-row" href="navis://urls/">
        <span><strong>${escapeHTML(t("internal.urls.title"))}</strong><small>${escapeHTML(t("settings.urlsDescription"))}</small></span>
        <span aria-hidden="true">›</span>
      </a>
    </section>
    <footer class="about-footer" data-settings-card data-search="copyright author open source william varmus 冷曜 开源 作者">
      <p class="about-footer-product">${escapeHTML(t("app.name"))}</p>
      <p>${escapeHTML(t("settings.aboutCopyright"))}</p>
      <p>${escapeHTML(t("settings.aboutOpenSourceBefore"))}<a href="https://firefox-source-docs.mozilla.org/overview/gecko.html" target="_blank" rel="noopener noreferrer">Mozilla Gecko</a>${escapeHTML(t("settings.aboutOpenSourceMiddle"))}<a href="navis://credits/">${escapeHTML(t("settings.aboutOpenSourceOther"))}</a>${escapeHTML(t("settings.aboutOpenSourceAfter"))}</p>
    </footer>`;
}

function settingsShell(page, diagnostics, t) {
  const content = settingsRoot(t) + `<section data-settings-section="help" hidden>${settingsHelp(diagnostics, t)}</section>`;
  return `
    <header class="settings-toolbar">
      <a class="settings-brand" href="navis://settings/" aria-label="${escapeHTML(t("settings.navisSettings"))}">${renderNavisMark("navis-settings", { variant: "compact" })}${escapeHTML(t("app.name"))}</a>
      <label class="settings-search">
        <span aria-hidden="true">⌕</span>
        <input id="settings-search" type="search" autocomplete="off" placeholder="${escapeHTML(t("settings.search"))}" aria-label="${escapeHTML(t("settings.search"))}">
      </label>
    </header>
    <div class="settings-layout">
      ${settingsNavigation(page, t)}
      <main class="settings-content">
        ${content}
        <section id="settings-no-results" class="settings-no-results" hidden aria-live="polite">
          <h2>${escapeHTML(t("settings.noResults"))}</h2>
          <p>${escapeHTML(t("settings.tryAnotherSearch"))}</p>
        </section>
        <p id="settings-status" class="settings-status" role="status" aria-live="polite">${escapeHTML(t("settings.loading"))}</p>
      </main>
      <div class="settings-balance" aria-hidden="true"></div>
    </div>`;
}

function newTabPage(t) {
  return `
    <main class="newtab-content">
      <div class="newtab-mark">${renderNavisMark("navis-newtab", { animated: true })}</div>
      <h1>${escapeHTML(t("app.name"))}</h1>
      <form id="newtab-search-form" class="newtab-search">
        <label class="sr-only" for="newtab-search">${escapeHTML(t("internal.searchOrAddress"))}</label>
        <input id="newtab-search" type="text" autocomplete="off" autofocus placeholder="${escapeHTML(t("internal.searchOrAddress"))}" aria-label="${escapeHTML(t("internal.searchOrAddress"))}">
      </form>
      <nav class="newtab-links" aria-label="${escapeHTML(t("internal.navisShortcuts"))}">
        <a href="navis://history/">${escapeHTML(t("library.history"))}</a>
        <a href="navis://bookmarks/">${escapeHTML(t("library.bookmarks"))}</a>
        <a href="navis://settings/">${escapeHTML(t("menu.settings"))}</a>
      </nav>
      <p id="management-status" class="management-status" role="status" aria-live="polite"></p>
    </main>`;
}

function managementToolbar(page, t) {
  const controls = {
    history: `
      <label class="management-search">
        <span class="sr-only">${escapeHTML(t("history.search"))}</span>
        <input id="management-search" type="search" autocomplete="off" placeholder="${escapeHTML(t("history.search"))}" aria-label="${escapeHTML(t("history.search"))}">
      </label>
      <button id="management-clear" class="management-action" type="button">${escapeHTML(t("history.clear"))}</button>`,
    bookmarks: `
      <button id="bookmark-back" class="management-action" type="button" disabled>${escapeHTML(t("bookmarks.backParent"))}</button>`,
    passwords: `
      <button id="management-clear" class="management-action" type="button">${escapeHTML(t("passwords.clear"))}</button>`,
    downloads: "",
  }[page.key];
  return `<div class="management-toolbar">${controls}</div>`;
}

function bookmarkAddForm(t) {
  return `
    <form id="bookmark-add-form" class="management-add-card">
      <h2>${escapeHTML(t("bookmarks.addItem"))}</h2>
      <label>${escapeHTML(t("bookmarks.itemType"))}
        <select name="type" aria-label="${escapeHTML(t("bookmarks.itemType"))}">
          <option value="bookmark">${escapeHTML(t("bookmarks.typeBookmark"))}</option>
          <option value="folder">${escapeHTML(t("bookmarks.typeFolder"))}</option>
        </select>
      </label>
      <label>${escapeHTML(t("bookmarks.name"))}
        <input name="title" type="text" maxlength="512" required>
      </label>
      <label>${escapeHTML(t("bookmarks.url"))}
        <input name="url" type="text" maxlength="4096" placeholder="https://example.com/">
      </label>
      <button class="management-action primary" type="submit">${escapeHTML(t("common.add"))}</button>
    </form>`;
}

function managementPage(page, t) {
  return `
    <header class="management-header">
      <div>
        <p class="product">${escapeHTML(t("app.name"))}</p>
        <h1>${escapeHTML(t(page.titleId))}</h1>
        <p>${escapeHTML(t(page.descriptionId))}</p>
      </div>
      <nav aria-label="${escapeHTML(t("internal.productPages"))}">
        <a href="navis://settings/">${escapeHTML(t("menu.settings"))}</a>
      </nav>
    </header>
    ${managementToolbar(page, t)}
    ${page.key === "bookmarks" ? bookmarkAddForm(t) : ""}
    <main id="management-list" class="management-list" aria-live="polite"></main>
    <p id="management-status" class="management-status" role="status" aria-live="polite">${escapeHTML(t("management.loading"))}</p>`;
}

function extensionManagerPage(page, t) {
  return `
    <header class="management-header extensions-header">
      <div>
        <p class="product">${escapeHTML(t("app.name"))}</p>
        <h1>${escapeHTML(t(page.titleId))}</h1>
        <p>${escapeHTML(t(page.descriptionId))}</p>
      </div>
      <nav aria-label="${escapeHTML(t("internal.productPages"))}">
        <a href="navis://settings/">${escapeHTML(t("menu.settings"))}</a>
      </nav>
    </header>
    <div class="management-toolbar extensions-toolbar">
      <p>${escapeHTML(t("extensions.compatible"))}</p>
      <button id="extension-install" class="management-action primary" type="button">${escapeHTML(t("extensions.installFromFile"))}</button>
    </div>
    <main id="extension-list" class="extension-list" aria-live="polite">
      <section class="extension-group" aria-labelledby="built-in-extensions-heading">
        <h2 id="built-in-extensions-heading">${escapeHTML(t("extensions.builtInSection"))}</h2>
        <div id="built-in-extensions" class="extension-grid"></div>
        <p id="built-in-extensions-empty" class="extension-empty" hidden>${escapeHTML(t("extensions.noBuiltIns"))}</p>
      </section>
      <section class="extension-group" aria-labelledby="user-extensions-heading">
        <h2 id="user-extensions-heading">${escapeHTML(t("extensions.userSection"))}</h2>
        <div id="user-extensions" class="extension-grid"></div>
        <p id="user-extensions-empty" class="extension-empty" hidden>${escapeHTML(t("extensions.noUserExtensions"))}</p>
      </section>
    </main>
    <p id="extension-status" class="management-status" role="status" aria-live="polite">${escapeHTML(t("extensions.loading"))}</p>
    <dialog id="extension-review" class="extension-review" aria-labelledby="extension-review-title">
      <form method="dialog" class="extension-review-card">
        <header>
          <div id="extension-review-mark" class="extension-mark" aria-hidden="true">E</div>
          <div>
            <h2 id="extension-review-title">${escapeHTML(t("extensions.reviewTitle"))}</h2>
            <p id="extension-review-summary"></p>
          </div>
        </header>
        <div id="extension-review-details" class="extension-review-details"></div>
        <p id="extension-review-compatibility" class="extension-compatibility"></p>
        <footer>
          <button id="extension-review-cancel" class="management-action" type="button">${escapeHTML(t("common.cancel"))}</button>
          <button id="extension-review-confirm" class="management-action primary" type="button"></button>
        </footer>
      </form>
    </dialog>`;
}

function pageBody(page, pages, diagnostics, t) {
  const additional = renderAdditionalProductPage(page, t, escapeHTML);
  if (additional !== null) {
    return additional;
  }
  if (page.id === "newtab") {
    return newTabPage(t);
  }
  if (["history", "bookmarks", "passwords", "downloads"].includes(page.id)) {
    return managementPage(page, t);
  }
  if (page.id === "extensions") {
    return extensionManagerPage(page, t);
  }
  if (page.id === "settings") {
    return settingsShell(page, diagnostics, t);
  }
  if (page.id === "urls") {
    return `
      <section class="card" aria-labelledby="available-pages">
        <h2 id="available-pages">${escapeHTML(t("internal.availablePages"))}</h2>
        <ul class="page-list">${pageList(pages, t)}</ul>
      </section>`;
  }
  if (page.id === "support") {
    return `
      <div class="diagnostics-actions">
        <button id="copy-diagnostics" class="diagnostics-copy" type="button" disabled>${escapeHTML(t("diagnostics.copy"))}</button>
        <p id="copy-diagnostics-status" role="status" aria-live="polite"></p>
      </div>
      <section class="card diagnostics-card" aria-labelledby="application-details">
        <h2 id="application-details">${escapeHTML(t("internal.application"))}</h2>
        <dl>${diagnosticRows(diagnostics.application, t)}</dl>
      </section>
      <section class="card diagnostics-card" aria-labelledby="engine-details">
        <h2 id="engine-details">${escapeHTML(t("internal.engine"))}</h2>
        <dl>${diagnosticRows(diagnostics.engine, t)}</dl>
      </section>
      <section class="card diagnostics-card" aria-labelledby="graphics-details">
        <h2 id="graphics-details">${escapeHTML(t("internal.graphics"))}</h2>
        <dl>${diagnosticRows(diagnostics.graphics || [], t)}</dl>
      </section>
      <section class="card diagnostics-card" aria-labelledby="media-details">
        <h2 id="media-details">${escapeHTML(t("internal.media"))}</h2>
        <dl>${diagnosticRows(diagnostics.media || [], t)}</dl>
      </section>
      <section class="card diagnostics-card" aria-labelledby="network-details">
        <h2 id="network-details">${escapeHTML(t("internal.network"))}</h2>
        <dl>${diagnosticRows(diagnostics.network || [], t)}</dl>
      </section>
      <section class="card diagnostics-card" aria-labelledby="capability-details">
        <h2 id="capability-details">${escapeHTML(t("internal.capabilities"))}</h2>
        <dl>${diagnosticRows(diagnostics.capabilities || [], t)}</dl>
      </section>
      <section class="card diagnostics-card" aria-labelledby="system-details">
        <h2 id="system-details">${escapeHTML(t("internal.system"))}</h2>
        <dl>${diagnosticRows(diagnostics.system, t)}</dl>
      </section>
      ${renderAdditionalSupport(t, escapeHTML)}
      <section class="card note" aria-labelledby="privacy-note">
        <h2 id="privacy-note">${escapeHTML(t("internal.diagnosticsPrivacy"))}</h2>
        <p>${escapeHTML(t("internal.diagnosticsPrivacyDescription"))}</p>
      </section>`;
  }
  throw new TypeError(`No internal-page renderer for ${page.key}`);
}

export function renderNavisInternalPage({
  page,
  pages,
  diagnostics,
  locale = "en-US",
  nonce,
}) {
  const localizer = createNavisLocalizer(locale);
  const t = localizer.text;
  const title = escapeHTML(t(page.titleId));
  const isSettings = page.id === "settings";
  const scripts = [];
  const hasClientScript =
    ["processes", "credits"].includes(page.key) ||
    isSettings ||
    ["newtab", "history", "bookmarks", "passwords", "downloads"].includes(
      page.key
    ) ||
    page.key === "extensions" ||
    page.key === "support" ||
    page.key === "settings/help";
  if (hasClientScript) {
    scripts.push(clientLocalizationScript(localizer.locale));
  }
  scripts.push(MATERIAL_INTERACTION_SCRIPT);
  if (isSettings || page.key === "newtab") {
    scripts.push(NAVIS_BRAND_SCRIPT);
  }
  if (!isSettings) {
    scripts.push(APPEARANCE_SCRIPT);
  }
  if (["processes", "credits"].includes(page.key)) {
    scripts.push(PRODUCT_PAGES_SCRIPT);
  }
  if (isSettings) {
    scripts.push(SETTINGS_SCRIPT);
  }
  if (
    ["newtab", "history", "bookmarks", "passwords", "downloads"].includes(
      page.key
    )
  ) {
    scripts.push(MANAGEMENT_SCRIPT);
  }
  if (page.key === "extensions") {
    scripts.push(EXTENSIONS_SCRIPT);
  }
  if (page.key === "support" || isSettings) {
    scripts.push(DIAGNOSTICS_SCRIPT);
  }
  const scriptPolicy = scripts.length
    ? `script-src 'nonce-${escapeHTML(nonce)}'`
    : "script-src 'none'";
  const imagePolicy =
    page.key === "extensions"
      ? "img-src data: moz-extension:"
      : "img-src 'none'";
  if (page.key === "profiles") {
    scripts.push(profilesPageScript(localizer.locale));
  }
  const body = page.key === "profiles" ? renderProfilesPage(localizer.locale) : pageBody(page, pages, diagnostics, t);
  const isStandaloneProductPage = [
    "newtab",
    "history",
    "bookmarks",
    "passwords",
    "downloads",
    "extensions",
  ].includes(page.key);
  const content =
    isSettings || isStandaloneProductPage
      ? body
      : `<main class="internal-content">
      <header class="internal-heading">
        <p class="product">${renderNavisMark("navis-page-heading", { variant: "compact" })}${escapeHTML(t("siteInfo.internal.toolbarTitle"))}</p>
        <h1>${title}</h1>
        <p>${escapeHTML(t(page.descriptionId))}</p>
      </header>
      ${body}
    </main>`;
  const script = scripts
    .map(source => `<script nonce="${escapeHTML(nonce)}">${source}</script>`)
    .join("");
  return `<!doctype html>
<html lang="${escapeHTML(localizer.locale)}" dir="ltr" data-page-key="${escapeHTML(page.key)}">
  <head>
    <meta charset="utf-8">
    <meta name="color-scheme" content="light dark">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'nonce-${escapeHTML(nonce)}'${page.key === "profiles" ? " chrome://navis/content/profile-ui.css" : ""}; ${imagePolicy}; ${scriptPolicy}; connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'">
    <title>${title}</title>
    ${page.key === "profiles" ? '<link rel="stylesheet" href="chrome://navis/content/profile-ui.css">' : ""}
    <style nonce="${escapeHTML(nonce)}">
      ${PRODUCT_PAGES_STYLE}
      ${NAVIS_BRAND_STYLE}
      :root { color-scheme: light dark; font: 14px/1.5 system-ui, sans-serif; --background: #f8fafd; --surface: #fff; --surface-soft: #eef3fc; --text: #1f1f1f; --muted: #5f6368; --line: #dadce0; --accent: #0b57d0; --focus: #0b57d0; --danger: #b3261e; }
      :root { --navis-color-selection: color-mix(in srgb, var(--accent) 20%, var(--surface)); --navis-color-on-selection: var(--text); }
      ::selection { background-color: var(--navis-color-selection); color: var(--navis-color-on-selection); }
      @media (forced-colors: active) { :root { --navis-color-selection: Highlight; --navis-color-on-selection: HighlightText; } }
      * { box-sizing: border-box; }
      body { min-height: 100vh; margin: 0; background: var(--background); color: var(--text); }
      button, input, select { font: inherit; }
      button, a[href], [role="button"] { position: relative; isolation: isolate; overflow: hidden; }
      .material-ripple { position: absolute; z-index: 0; border-radius: 50%; background: currentColor; opacity: 0; pointer-events: none; transform: scale(0); animation: material-ripple 480ms cubic-bezier(.2, 0, 0, 1); }
      @keyframes material-ripple { 20% { opacity: .12; } 100% { opacity: 0; transform: scale(1); } }
      a { color: var(--accent); }
      :focus-visible { outline: 3px solid color-mix(in srgb, var(--focus) 35%, transparent); outline-offset: 2px; }
      .internal-content { width: min(880px, calc(100% - 32px)); margin: 0 auto; padding: 56px 0 80px; }
      .internal-heading { margin: 0 0 32px; }
      .product { display: flex; align-items: center; gap: 8px; margin: 0 0 8px; color: var(--text); font-size: 13px; font-weight: 600; letter-spacing: .04em; text-transform: uppercase; }
      .product .navis-brand-mark { width: 24px; height: 24px; }
      h1 { margin: 0; font-size: clamp(28px, 4vw, 40px); font-weight: 500; letter-spacing: -.02em; }
      .internal-heading > p:last-child { max-width: 680px; margin: 12px 0 0; color: var(--muted); font-size: 16px; }
      .card, .settings-card { margin: 16px 0; padding: 24px; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); box-shadow: 0 1px 2px rgb(60 64 67 / 8%); }
      h2 { margin: 0 0 16px; font-size: 18px; font-weight: 500; }
      dl { margin: 0; }
      .diagnostics-actions { display: flex; min-height: 48px; align-items: center; gap: 16px; margin: 0 0 8px; }
      .diagnostics-copy { min-height: 40px; padding: 0 18px; border: 1px solid var(--accent); border-radius: 20px; background: var(--accent); color: white; font-weight: 600; cursor: pointer; }
      .diagnostics-copy:hover:not(:disabled) { background: color-mix(in srgb, var(--accent) 88%, black); }
      .diagnostics-copy:disabled { cursor: default; opacity: .5; }
      .diagnostics-actions p { margin: 0; color: var(--muted); }
      .diagnostics-copy-source { position: fixed; inset: auto auto 0 -10000px; width: 1px; height: 1px; opacity: 0; }
      .diagnostic-row { display: grid; grid-template-columns: minmax(150px, 1fr) minmax(0, 2fr); gap: 20px; padding: 12px 0; border-top: 1px solid var(--line); }
      .diagnostic-row:first-child { padding-top: 0; border-top: 0; }
      dt { color: var(--muted); }
      dd { min-width: 0; margin: 0; overflow-wrap: anywhere; font-family: ui-monospace, monospace; }
      .page-list { margin: 0; padding: 0; list-style: none; }
      .page-list li { display: grid; gap: 4px; padding: 16px 0; border-top: 1px solid var(--line); }
      .page-list li:first-child { padding-top: 0; border-top: 0; }
      .page-list a { width: fit-content; font: 600 15px ui-monospace, monospace; text-decoration: none; }
      a:hover { text-decoration: underline; }
      .page-list span, .note p { color: var(--muted); }
      .note p { margin: 0; }
      .sr-only { position: absolute !important; width: 1px !important; height: 1px !important; padding: 0 !important; overflow: hidden !important; clip: rect(0, 0, 0, 0) !important; white-space: nowrap !important; border: 0 !important; }
      .newtab-content { display: grid; width: min(720px, calc(100% - 40px)); min-height: 75vh; margin: 0 auto; align-content: center; justify-items: center; }
      .newtab-mark { width: 90px; height: 90px; margin-bottom: 12px; }
      .newtab-content h1 { font-size: 36px; }
      .newtab-search { width: min(620px, 100%); margin: 28px 0 20px; }
      .newtab-search input { width: 100%; min-height: 48px; padding: 0 22px; border: 1px solid transparent; border-radius: 24px; outline: 0; background: var(--surface); box-shadow: 0 2px 8px rgb(60 64 67 / 20%); color: var(--text); }
      .newtab-search input:focus { border-color: var(--accent); box-shadow: 0 2px 8px rgb(60 64 67 / 20%), 0 0 0 2px color-mix(in srgb, var(--focus) 25%, transparent); }
      .newtab-links { display: flex; flex-wrap: wrap; justify-content: center; gap: 12px; }
      .newtab-links a { min-width: 104px; padding: 10px 16px; border-radius: 20px; background: var(--surface); color: var(--text); text-align: center; text-decoration: none; }
      .newtab-links a:hover { background: var(--surface-soft); text-decoration: none; }
      .management-header, .management-toolbar, .management-add-card, .management-list, .management-status { width: min(980px, calc(100% - 40px)); margin-inline: auto; }
      .management-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; padding: 48px 0 24px; border-bottom: 1px solid var(--line); }
      .management-header > div > p:last-child { margin: 8px 0 0; color: var(--muted); }
      .management-header nav { display: flex; gap: 12px; }
      .management-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 20px 0; }
      .management-search { min-width: min(420px, 100%); }
      .management-search input, .management-add-card input, .management-add-card select, .settings-select { width: 100%; min-height: 40px; padding: 0 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); color: var(--text); }
      .settings-select-label { min-width: 180px; }
      .management-action { min-height: 36px; padding: 0 16px; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); color: var(--accent); font-weight: 600; cursor: pointer; }
      .management-action:hover:not(:disabled) { background: var(--surface-soft); }
      .management-action.primary { border-color: var(--accent); background: var(--accent); color: white; }
      .management-action:disabled { color: var(--muted); cursor: default; opacity: .55; }
      .management-add-card { display: grid; grid-template-columns: 140px minmax(180px, 1fr) minmax(240px, 2fr) auto; align-items: end; gap: 16px; margin-bottom: 20px; padding: 20px; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); }
      .management-add-card h2 { grid-column: 1 / -1; margin-bottom: 0; }
      .management-add-card label { display: grid; gap: 6px; color: var(--muted); font-size: 13px; }
      .management-list { overflow: hidden; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); }
      .management-list:empty::before { display: block; min-height: 112px; content: ""; }
      .management-row { display: grid; min-height: 70px; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 20px; padding: 12px 18px; border-top: 1px solid var(--line); }
      .management-row:first-child { border-top: 0; }
      .management-row:hover { background: color-mix(in srgb, var(--text) 5%, transparent); }
      .management-row-link { display: block; min-width: 0; padding: 0; border: 0; background: transparent; color: var(--text); text-align: start; text-decoration: none; cursor: pointer; }
      .management-row-link:hover { text-decoration: none; }
      .management-row-copy { display: grid; min-width: 0; gap: 3px; }
      .management-row-copy strong, .management-row-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .management-row-copy small { color: var(--muted); }
      .management-row-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
      .management-download-copy { gap: 6px; }
      .management-download-copy small { overflow: visible; white-space: normal; overflow-wrap: anywhere; }
      .management-download-row .management-action { white-space: nowrap; }
      .management-download-progress { height: 4px; overflow: hidden; border-radius: 2px; background: color-mix(in srgb, var(--accent) 18%, var(--surface)); }
      .management-download-progress > span { display: block; width: 100%; height: 100%; border-radius: inherit; background: var(--accent); transform-origin: left center; }
      .management-download-progress[data-indeterminate] > span { width: 40%; animation: download-progress-slide 1.2s ease-in-out infinite; }
      @keyframes download-progress-slide { from { transform: translateX(-100%); } to { transform: translateX(350%); } }
      @media (prefers-reduced-motion: reduce) { .management-download-progress[data-indeterminate] > span { animation: none; transform: translateX(75%); } }
      @media (max-width: 560px) { .management-download-row { grid-template-columns: minmax(0, 1fr); gap: 12px; } .management-download-row .management-row-actions { justify-content: flex-start; } }
      .management-secret { display: block; width: fit-content; max-width: 100%; margin-top: 6px; overflow: hidden; font-family: ui-monospace, monospace; text-overflow: ellipsis; white-space: nowrap; }
      .management-status { min-height: 24px; padding: 16px 4px 64px; color: var(--muted); }
      .management-status[data-pending]::before { display: inline-block; width: 12px; height: 12px; margin-right: 8px; border: 2px solid var(--line); border-top-color: var(--accent); border-radius: 50%; content: ""; vertical-align: -2px; animation: settings-spin 700ms linear infinite; }
      .extensions-toolbar p { margin: 0; color: var(--muted); }
      .extension-list { display: grid; width: min(980px, calc(100% - 40px)); margin: 0 auto; gap: 28px; }
      .extension-group > h2 { margin: 0 0 14px 4px; }
      .extension-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
      .extension-card { display: grid; min-height: 236px; grid-template-columns: 48px minmax(0, 1fr) auto; grid-template-rows: auto 1fr auto; align-items: start; gap: 16px; padding: 20px; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); box-shadow: 0 1px 2px rgb(60 64 67 / 8%); }
      .extension-mark { display: grid; width: 48px; height: 48px; flex: 0 0 48px; border-radius: 14px; background: color-mix(in srgb, var(--accent) 16%, var(--surface)); color: var(--accent); font-size: 22px; font-weight: 650; place-items: center; }
      .extension-mark img { width: 32px; height: 32px; object-fit: contain; }
      .extension-card-copy { min-width: 0; }
      .extension-card-heading { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 8px; }
      .extension-card h3 { min-width: 0; margin: 0; overflow: hidden; font-size: 17px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
      .extension-badges { display: flex; flex-wrap: wrap; gap: 5px; }
      .extension-badge { padding: 2px 8px; border-radius: 10px; background: var(--surface-soft); color: var(--muted); font-size: 11px; font-weight: 600; white-space: nowrap; }
      .extension-meta { margin: 5px 0 0; overflow: hidden; color: var(--muted); font: 12px ui-monospace, monospace; text-overflow: ellipsis; white-space: nowrap; }
      .extension-description { display: -webkit-box; margin: 10px 0 0; overflow: hidden; color: var(--muted); -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
      .extension-card-controls { padding-top: 2px; }
      .extension-toggle { display: block; }
      .extension-permissions { grid-column: 2 / -1; align-self: end; min-width: 0; color: var(--muted); }
      .extension-permissions summary { width: fit-content; color: var(--accent); cursor: pointer; font-size: 13px; font-weight: 600; }
      .extension-permission-list { margin: 10px 0 0; padding-left: 20px; overflow-wrap: anywhere; color: var(--muted); font: 12px/1.6 ui-monospace, monospace; }
      .extension-private-access { display: flex; grid-column: 2 / -1; align-items: center; justify-content: space-between; gap: 16px; color: var(--muted); font-size: 13px; }
      .extension-actions { display: flex; grid-column: 1 / -1; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 8px; padding-top: 14px; border-top: 1px solid var(--line); }
      .management-action.danger { border-color: var(--danger); color: var(--danger); }
      .extension-empty { min-height: 112px; margin: 0; padding: 44px 20px; border: 1px dashed var(--line); border-radius: 16px; color: var(--muted); text-align: center; }
      .extension-review { width: min(620px, calc(100% - 32px)); max-height: min(760px, calc(100vh - 48px)); padding: 0; overflow: auto; border: 0; border-radius: 20px; background: var(--surface); color: var(--text); box-shadow: 0 24px 64px rgb(0 0 0 / 32%); }
      .extension-review::backdrop { background: rgb(32 33 36 / 46%); backdrop-filter: blur(2px); }
      .extension-review-card { display: grid; gap: 20px; padding: 24px; }
      .extension-review-card > header { display: flex; align-items: center; gap: 16px; }
      .extension-review-card h2, .extension-review-card header p { margin: 0; }
      .extension-review-card header p { margin-top: 4px; color: var(--muted); }
      .extension-review-details { display: grid; max-height: 380px; gap: 18px; overflow: auto; }
      .extension-review-details h3 { margin: 0 0 8px; font-size: 14px; font-weight: 600; }
      .extension-review-identity { padding: 14px 16px; border-radius: 12px; background: var(--surface-soft); }
      .extension-review-identity p { margin: 3px 0; overflow-wrap: anywhere; color: var(--muted); font: 12px/1.5 ui-monospace, monospace; }
      .extension-compatibility { margin: 0; padding: 12px 14px; border-radius: 12px; background: color-mix(in srgb, var(--accent) 10%, var(--surface)); color: var(--accent); }
      .extension-compatibility[data-incompatible] { background: color-mix(in srgb, var(--danger) 10%, var(--surface)); color: var(--danger); }
      .extension-review-card > footer { display: flex; justify-content: flex-end; gap: 10px; }
      .settings-toolbar { position: sticky; z-index: 3; top: 0; display: grid; min-height: 64px; grid-template-columns: 266px minmax(280px, 680px) 1fr; align-items: center; padding: 0 24px; border-bottom: 1px solid var(--line); background: color-mix(in srgb, var(--background) 94%, transparent); backdrop-filter: blur(12px); }
      .settings-brand { display: inline-flex; align-items: center; gap: 8px; color: var(--text); font-size: 21px; font-weight: 500; text-decoration: none; }
      .settings-brand .navis-brand-mark { width: 36px; height: 36px; }
      .settings-search { display: flex; min-height: 40px; align-items: center; gap: 10px; padding: 0 16px; border-radius: 20px; background: var(--surface-soft); box-shadow: 0 1px 2px rgb(60 64 67 / 12%); color: var(--muted); }
      .settings-search:focus-within { box-shadow: 0 1px 2px rgb(60 64 67 / 12%), 0 0 0 2px color-mix(in srgb, var(--focus) 30%, transparent); }
      .settings-search input { width: 100%; border: 0; outline: 0; background: transparent; color: var(--text); }
      .settings-search input::placeholder { color: var(--muted); }
      .settings-layout { display: grid; min-height: calc(100vh - 64px); grid-template-columns: 266px minmax(0, 680px) 1fr; gap: 0; padding: 0 24px; }
      .settings-nav { position: sticky; top: 64px; display: flex; height: fit-content; max-height: calc(100vh - 64px); flex-direction: column; gap: 4px; padding: 24px 24px 24px 0; overflow: auto; }
      .settings-nav a { display: flex; min-height: 40px; align-items: center; gap: 14px; padding: 0 16px; border-radius: 20px; color: var(--text); font-weight: 500; text-decoration: none; }
      .settings-nav-icon { width: 20px; height: 20px; flex: 0 0 20px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
      .settings-nav a:hover { background: color-mix(in srgb, var(--text) 7%, transparent); }
      .settings-nav a[aria-current="page"] { background: color-mix(in srgb, var(--accent) 14%, var(--surface)); color: var(--accent); }
      .settings-nav-separator { height: 1px; margin: 8px 0; background: var(--line); }
      .settings-content { min-width: 0; padding: 40px 0 80px; }
      .settings-page-heading { padding: 0 4px 12px; }
      .settings-page-heading h1 { font-size: 28px; }
      .settings-page-heading p { margin: 8px 0 0; color: var(--muted); }
      .settings-card { padding: 0; overflow: hidden; }
      .settings-card > h2 { padding: 20px 24px 4px; }
      .settings-row { display: flex; min-height: 88px; align-items: center; justify-content: space-between; gap: 24px; padding: 20px 24px; }
      .settings-row-stacked { align-items: flex-start; flex-direction: column; }
      .settings-row-copy { min-width: 0; }
      .settings-row-copy h2 { margin-bottom: 4px; }
      .settings-row-copy p { margin: 0; overflow-wrap: anywhere; color: var(--muted); }
      .settings-row-copy .settings-feature-detail { max-width: 540px; margin-top: 8px; font-size: 13px; line-height: 1.55; }
      .settings-language-controls { display: flex; flex: none; flex-direction: column; align-items: flex-end; gap: 10px; }
      .settings-choice-group { display: grid; width: 100%; gap: 8px; margin: 0; padding: 0; border: 0; }
      .settings-choice { position: relative; display: grid; min-height: 72px; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: center; gap: 14px; padding: 12px 16px; border-radius: 12px; cursor: pointer; }
      .settings-choice:hover { background: color-mix(in srgb, var(--text) 6%, transparent); }
      .settings-choice input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
      .settings-choice-indicator { display: grid; width: 18px; height: 18px; border: 2px solid var(--muted); border-radius: 50%; place-items: center; }
      .settings-choice-indicator::after { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); content: ""; opacity: 0; transform: scale(.45); transition: opacity 120ms ease, transform 120ms ease; }
      .settings-choice input:checked + .settings-choice-indicator { border-color: var(--accent); }
      .settings-choice input:checked + .settings-choice-indicator::after { opacity: 1; transform: scale(1); }
      .settings-choice input:focus-visible + .settings-choice-indicator { box-shadow: 0 0 0 3px color-mix(in srgb, var(--focus) 28%, transparent); }
      .settings-choice-copy { display: grid; gap: 3px; }
      .settings-choice-copy strong { font-weight: 600; }
      .settings-choice-copy small, .settings-relaunch-row p { color: var(--muted); font-size: 13px; line-height: 1.45; }
      .settings-recommended { padding: 3px 9px; border-radius: 10px; background: color-mix(in srgb, var(--accent) 14%, var(--surface)); color: var(--accent); font-size: 11px; font-weight: 600; }
      .settings-choice-group:disabled { opacity: .55; }
      .settings-choice-group:disabled .settings-choice { cursor: default; }
      .settings-relaunch-row { display: flex; width: 100%; min-height: 36px; align-items: center; justify-content: space-between; gap: 16px; padding: 0 16px; }
      .settings-relaunch-row p { margin: 0; }
      .settings-help-link { display: inline-flex; width: fit-content; min-height: 32px; align-items: center; gap: 6px; margin: 8px 0 -6px -10px; padding: 0 10px; border-radius: 16px; font-weight: 500; text-decoration: none; }
      .settings-help-link:hover { background: color-mix(in srgb, var(--accent) 8%, transparent); text-decoration: none; }
      .settings-switch { position: relative; width: 36px; min-width: 36px; height: 20px; padding: 0; border: 0; border-radius: 10px; background: var(--muted); cursor: pointer; }
      .settings-switch-thumb { position: absolute; top: 3px; left: 3px; width: 14px; height: 14px; border-radius: 50%; background: var(--surface); box-shadow: 0 1px 2px rgb(0 0 0 / 30%); transition: transform 120ms ease; }
      .settings-switch[aria-checked="true"] { background: var(--accent); }
      .settings-switch[aria-checked="true"] .settings-switch-thumb { transform: translateX(16px); }
      .settings-switch:disabled { cursor: default; opacity: .38; }
      .settings-action { flex-shrink: 0; min-height: 36px; padding: 0 16px; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); color: var(--accent); font-weight: 600; white-space: nowrap; cursor: pointer; }
      .settings-action:hover:not(:disabled) { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
      .settings-action:disabled { opacity: .38; cursor: default; }
      .settings-select:disabled { opacity: .55; }
      .settings-provider-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 24px 0; }
      .settings-provider-header h2 { margin: 0; }
      .settings-provider-description { margin: 0; padding: 4px 24px 20px; color: var(--muted); overflow-wrap: anywhere; }
      .settings-provider-row { position: relative; display: grid; grid-template-columns: 40px minmax(0, 1fr) 80px; align-items: center; gap: 12px; padding: 12px 24px; border-top: 1px solid var(--line); }
      .settings-provider-row[data-dragging] { background: color-mix(in srgb, var(--accent) 7%, transparent); }
      .settings-provider-row[data-drop]::after { position: absolute; inset-inline: 16px; height: 2px; background: var(--accent); pointer-events: none; content: ""; }
      .settings-provider-row[data-drop="before"]::after { top: 0; }
      .settings-provider-row[data-drop="after"]::after { bottom: 0; }
      .settings-provider-copy { display: grid; min-width: 0; gap: 4px; overflow-wrap: anywhere; }
      .settings-provider-copy small { color: var(--muted); overflow-wrap: anywhere; }
      .settings-provider-actions { display: flex; align-items: center; }
      .settings-provider-icon { display: inline-grid; place-items: center; flex: 0 0 40px; width: 40px; height: 40px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--muted); cursor: pointer; }
      .settings-provider-icon svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; pointer-events: none; }
      .settings-provider-icon:hover:not(:disabled) { background: color-mix(in srgb, var(--text) 8%, transparent); }
      .settings-provider-icon:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
      .settings-provider-icon:disabled { opacity: .38; cursor: default; }
      .settings-provider-handle { cursor: grab; user-select: none; }
      .settings-provider-handle:active { cursor: grabbing; }
      .settings-provider-handle path { stroke-width: 3; }
      .settings-dialog { max-width: min(560px, calc(100vw - 32px)); width: 100%; padding: 24px; border: 0; border-radius: 24px; background: var(--surface); color: var(--text); box-shadow: 0 8px 24px rgb(31 31 31 / 24%); }
      .settings-dialog::backdrop { background: rgb(31 31 31 / 32%); }
      .settings-dialog form, .settings-dialog label { display: grid; gap: 12px; }
      .settings-dialog input { width: 100%; min-height: 40px; padding: 8px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); color: var(--text); }
      .settings-dialog p { margin: 0; color: var(--muted); }
      .settings-dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
      .settings-theme-choices { display: flex; flex-wrap: wrap; gap: 16px; width: 100%; border: 0; padding: 0; }
      .settings-theme-choice { display: grid; grid-template-columns: auto 1fr; gap: 8px; cursor: pointer; }
      .settings-theme-choice input { align-self: center; accent-color: var(--accent); }
      .settings-theme-preview { position: relative; display: block; grid-column: 1 / -1; grid-row: 1; width: 140px; height: 92px; overflow: hidden; border: 2px solid var(--line); border-radius: 12px; background: #ffffff; }
      .settings-theme-preview i { position: absolute; inset: 0 0 auto; height: 24px; background: #e6e8ec; }
      .settings-theme-preview b { position: absolute; inset: 5px 55px auto 5px; height: 19px; background: #ffffff; border-radius: 6px 6px 0 0; }
      .settings-theme-preview em { position: absolute; top: 34px; left: 16px; width: 48px; height: 8px; border-radius: 4px; background: var(--accent); }
      .settings-theme-preview[data-preview="dark"] { background: #292a2d; }
      .settings-theme-preview[data-preview="dark"] i { background: #17181a; }
      .settings-theme-preview[data-preview="dark"] b { background: #292a2d; }
      .settings-theme-preview[data-preview="system"] { background: linear-gradient(90deg, #fff 50%, #292a2d 50%); }
      .settings-theme-preview[data-preview="system"] i { background: linear-gradient(90deg, #e6e8ec 50%, #17181a 50%); }
      .settings-theme-choice:has(input:checked) .settings-theme-preview { border-color: var(--accent); }
      .settings-theme-choice:has(input:focus-visible) .settings-theme-preview { outline: 2px solid var(--focus); outline-offset: 3px; }
      #appearance-accent { width: 64px; height: 36px; padding: 4px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); cursor: pointer; }
      .settings-accent-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
      #download-directory { overflow-wrap: anywhere; }
      .settings-status { min-height: 24px; margin: 16px 4px; color: var(--muted); }
      .settings-status[data-pending]::before { display: inline-block; width: 12px; height: 12px; margin-right: 8px; border: 2px solid var(--line); border-top-color: var(--accent); border-radius: 50%; content: ""; vertical-align: -2px; animation: settings-spin 700ms linear infinite; }
      .settings-no-results { padding: 72px 24px; text-align: center; }
      .settings-no-results p { color: var(--muted); }
      @media (prefers-reduced-motion: reduce) { .material-ripple { animation-duration: 1ms; } }
      .about-product { display: flex; align-items: center; gap: 20px; padding: 24px; }
      .about-product h2, .about-product p { margin: 0; }
      .about-product p { color: var(--muted); font-family: ui-monospace, monospace; }
      .about-mark { width: 65px; height: 65px; flex: 0 0 auto; }
      .about-card dl, .settings-card > dl { padding: 0 24px 20px; }
      .about-footer { margin: 44px 4px 0; padding: 24px 0 0; border-top: 1px solid var(--line); color: var(--muted); font-size: 13px; line-height: 1.55; }
      .about-footer p { margin: 3px 0; }
      .about-footer-product { color: var(--text); font-size: 14px; font-weight: 600; }
      .settings-link-row { display: flex; min-height: 64px; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 24px; border-top: 1px solid var(--line); color: var(--text); text-decoration: none; }
      .settings-link-row:hover { background: color-mix(in srgb, var(--text) 6%, transparent); text-decoration: none; }
      .settings-link-row span:first-child { display: grid; gap: 2px; }
      .settings-link-row small { color: var(--muted); font-size: 13px; }
      @keyframes settings-spin { to { transform: rotate(360deg); } }
      @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --background: #202124; --surface: #292a2d; --surface-soft: #303134; --text: #e8eaed; --muted: #bdc1c6; --line: #3c4043; --accent: #8ab4f8; --focus: #8ab4f8; --danger: #f2b8b5; } }
      :root[data-theme="dark"] { --background: #202124; --surface: #292a2d; --surface-soft: #303134; --text: #e8eaed; --muted: #bdc1c6; --line: #3c4043; --danger: #f2b8b5; }
      @media (max-width: 980px) { .settings-toolbar, .settings-layout { grid-template-columns: 220px minmax(0, 680px); } .settings-balance { display: none; } }
      @media (max-width: 760px) { .extension-grid { grid-template-columns: 1fr; } }
      @media (max-width: 720px) { .settings-toolbar { grid-template-columns: 1fr; gap: 10px; padding: 12px 16px; } .settings-brand { display: none; } .settings-layout { display: block; padding: 0 16px; } .settings-nav { position: static; flex-direction: row; padding: 16px 0 0; overflow: auto; } .settings-nav-separator { width: 1px; height: 40px; margin: 0 4px; } .settings-nav a { white-space: nowrap; } .settings-content { padding-top: 28px; } .management-header { align-items: flex-start; flex-direction: column; } .management-toolbar { align-items: stretch; flex-direction: column; } .management-search { min-width: 0; width: 100%; } .management-add-card { grid-template-columns: 1fr; } }
      @media (max-width: 560px) { .internal-content { padding-top: 32px; } .card { padding: 20px; } .diagnostic-row { grid-template-columns: 1fr; gap: 4px; } .settings-row { align-items: flex-start; } }
      @media (prefers-reduced-motion: reduce) { .settings-switch-thumb, .settings-choice-indicator::after { transition: none; } .settings-status[data-pending]::before, .management-status[data-pending]::before { animation-duration: 1400ms; } }
      @media (forced-colors: active) { .card, .settings-card { border: 1px solid CanvasText; box-shadow: none; } .settings-switch { border: 1px solid ButtonText; } .settings-nav a[aria-current="page"] { outline: 1px solid Highlight; } }
    </style>
  </head>
  <body class="${isSettings ? "settings-body" : "internal-body"}">
    ${content}
    ${script}
  </body>
</html>`;
}
