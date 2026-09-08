/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { profileMessages } from "./profile-locales.mjs";
import { showProfileDialog } from "./profile-ui.mjs";

function escapeHTML(value) {
  return String(value).replace(/[&<>"']/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

export function renderProfilesPage(locale) {
  const text = profileMessages(locale);
  return `<section aria-label="${escapeHTML(text.title)}">
    <div class="profile-actions"><button class="management-action" id="profiles-create" type="button">${escapeHTML(text.create)}</button></div>
    <p id="profiles-status" role="status">${escapeHTML(text.loading)}</p>
    <div id="profiles-list"></div>
    <section class="card"><p>${escapeHTML(text.restartDetails)}</p><div class="profile-actions">
      <button class="management-action" id="profiles-restart-safe" type="button">${escapeHTML(text.restartSafe)}</button>
      <button class="management-action" id="profiles-restart" type="button">${escapeHTML(text.restart)}</button>
    </div></section>
  </section>`;
}

function installProfilesPage(messages, openDialog) {
  const list = document.getElementById("profiles-list");
  const status = document.getElementById("profiles-status");
  let pending = null;
  let snapshot = null;
  let activeDialog = null;
  let openingEditor = false;
  let needsRender = false;
  let refreshQueued = false;
  const setStatus = message => { status.textContent = message; };
  const dispatch = (command, value) => {
    if (pending) {
      return Promise.reject(new Error("Profile operation already in progress"));
    }
    setStatus(messages.loading);
    const result = new Promise((resolve, reject) => { pending = { resolve, reject }; });
    document.dispatchEvent(new CustomEvent("NavisManagementCommand", {
      detail: value === undefined ? { command } : { command, value }, bubbles: true,
    }));
    return result;
  };
  const action = (label, callback, disabled = false) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "management-action";
    button.textContent = label;
    button.disabled = disabled;
    button.addEventListener("click", () => Promise.resolve().then(callback).catch(() => setStatus(messages.failed)));
    return button;
  };
  const refresh = () => {
    if (activeDialog || openingEditor || pending) {
      refreshQueued = true;
      return;
    }
    refreshQueued = false;
    dispatch("profiles:get").catch(() => setStatus(messages.failed));
  };
  const refreshIfQueued = () => {
    if (refreshQueued && !activeDialog && !openingEditor && !pending) refresh();
  };
  const trackDialog = dialog => {
    activeDialog = dialog;
    dialog.addEventListener("close", () => {
      activeDialog = null;
      if (needsRender) render();
      refreshIfQueued();
    }, { once: true });
  };
  const current = async () => {
    const fresh = await dispatch("profiles:get");
    const user = fresh.profiles.find(record => record.current);
    if (!user) throw new Error("Missing current profile");
    return user;
  };
  const edit = async () => {
    if (activeDialog || openingEditor || pending) return;
    openingEditor = true;
    try {
      const user = await current();
      trackDialog(openDialog(document, messages, {
        user, current, submit: value => dispatch("profile:update", value),
      }));
    } finally {
      openingEditor = false;
      if (!activeDialog && needsRender) render();
      refreshIfQueued();
    }
  };
  const remove = record => {
    const dialog = document.createElement("dialog");
    dialog.className = "profile-dialog";
    dialog.setAttribute("aria-label", messages.removeTitle);
    const title = document.createElement("h2");
    title.textContent = messages.removeTitle;
    const name = document.createElement("strong");
    name.textContent = record.userName;
    const description = document.createElement("p");
    description.textContent = messages.removeDetails;
    const actions = document.createElement("div");
    actions.className = "profile-actions";
    const commit = async deleteFiles => {
      await dispatch("profiles:remove", { id: record.id, deleteFiles });
      dialog.close();
    };
    actions.append(action(messages.cancel, () => dialog.close()), action(messages.keepFiles, () => commit(false)), action(messages.deleteFiles, () => commit(true)));
    dialog.append(title, name, description, actions);
    document.body.append(dialog);
    dialog.addEventListener("close", () => dialog.remove(), { once: true });
    trackDialog(dialog);
    dialog.showModal();
  };
  const render = () => {
    needsRender = false;
    const focused = document.activeElement;
    let restoreFocus = null;
    list.replaceChildren();
    for (const record of snapshot.profiles) {
      const card = document.createElement("article");
      card.className = "card profile-card";
      const title = document.createElement("h2");
      title.textContent = record.userName;
      const badges = document.createElement("small");
      badges.textContent = [record.current && messages.current, record.default && messages.default, !record.current && record.inUse && messages.inUse].filter(Boolean).join(" · ");
      const actions = document.createElement("div");
      actions.className = "profile-actions";
      if (record.current) {
        actions.append(action(messages.edit, edit));
      }
      if (!record.default) {
        actions.append(action(messages.setDefault, () => dispatch("profiles:set-default", { id: record.id }), snapshot.isListOutdated));
      }
      if (record.canLaunch) {
        actions.append(action(messages.launch, () => dispatch("profiles:launch", { id: record.id }), snapshot.isListOutdated));
      }
      if (record.canRemove) {
        actions.append(action(messages.remove, () => remove(record), snapshot.isListOutdated));
      }
      for (const button of actions.children) {
        button.dataset.profileId = record.id;
        if (focused?.dataset?.profileId === record.id && focused.textContent === button.textContent) {
          restoreFocus = button;
        }
      }
      card.append(title, badges, actions);
      list.append(card);
    }
    document.getElementById("profiles-create").disabled = snapshot.isListOutdated;
    if (restoreFocus && !restoreFocus.disabled) restoreFocus.focus();
  };
  window.addEventListener("NavisManagementState", event => {
    const state = event.detail;
    if (!state || state.pageKey !== "profiles") {
      return;
    }
    const completion = pending;
    pending = null;
    if (state.outcome === "operation-failed" || state.error || state.outcome === "failed") {
      setStatus(messages.failed);
      completion?.reject(new Error(messages.failed));
      refreshIfQueued();
      return;
    }
    const nextSnapshot = state.profileManagement;
    if (!nextSnapshot || !Array.isArray(nextSnapshot.profiles)) {
      setStatus(messages.failed);
      completion?.reject(new Error("Missing profile snapshot"));
      refreshIfQueued();
      return;
    }
    snapshot = nextSnapshot;
    if (activeDialog || openingEditor) needsRender = true;
    else render();
    setStatus(state.profileOperation?.launched === false ? messages.createdLaunchFailed : snapshot.isListOutdated ? messages.outdated : "");
    completion?.resolve(state.profileOperation ?? snapshot);
    refreshIfQueued();
  });
  document.getElementById("profiles-create").addEventListener("click", () => {
    if (activeDialog || openingEditor || pending) return;
    trackDialog(openDialog(document, messages, {
      mode: "create", submit: value => dispatch("profiles:create", value),
      launch: id => dispatch("profiles:launch", { id }),
    }));
  });
  document.getElementById("profiles-restart-safe").addEventListener("click", () => { dispatch("profiles:restart", { safeMode: true }).catch(() => setStatus(messages.failed)); });
  document.getElementById("profiles-restart").addEventListener("click", () => { dispatch("profiles:restart", { safeMode: false }).catch(() => setStatus(messages.failed)); });
  window.addEventListener("focus", refresh);
  window.addEventListener("pageshow", refresh);
  window.addEventListener("NavisProfileChanged", refresh);
  refresh();
}

export function profilesPageScript(locale) {
  const messages = JSON.stringify(profileMessages(locale)).replaceAll("<", "\\u003c");
  return `(${installProfilesPage.toString()})(${messages}, ${showProfileDialog.toString()});`;
}
