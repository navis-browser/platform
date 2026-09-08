/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { profileMessages } from "./profile-locales.mjs";
import { setIcon } from "./design-system.mjs";

export function profileAvatarPresentation(user) {
  const name = String(user?.userName || "").trim();
  const first = typeof Intl.Segmenter === "function"
    ? new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(name)[Symbol.iterator]().next().value?.segment
    : Array.from(name)[0];
  const background = /^#[0-9a-f]{6}$/i.test(user?.accentColor || "")
    ? user.accentColor.toLowerCase() : "#0b57d0";
  const channels = [1, 3, 5].map(offset => {
    const channel = parseInt(background.slice(offset, offset + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  const luminance = channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
  return {
    initial: first?.toLocaleUpperCase() || "?",
    background,
    color: (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05) ? "#000000" : "#ffffff",
  };
}

// Serialized into navis://profiles by profilesPageScript: keep all helpers
// inside this function, with no dependencies on the module's lexical scope.
export function showProfileDialog(document, messages, { user = null, submit, current = null, launch = null, mode = "edit", returnFocus = document.activeElement }) {
  const previousFocus = returnFocus;
  const dialog = document.createElement("dialog");
  dialog.className = "profile-dialog";
  const form = document.createElement("form");
  const title = document.createElement("h2");
  title.textContent = mode === "create" ? messages.create : messages.edit;
  title.id = `profile-dialog-title-${Math.random().toString(36).slice(2)}`;
  dialog.setAttribute("aria-labelledby", title.id);
  const description = document.createElement("p");
  description.className = "profile-dialog-description";
  description.textContent = mode === "create" ? messages.createDescription : messages.editDescription;
  const descriptionId = `${title.id}-description`;
  description.id = descriptionId;
  dialog.setAttribute("aria-describedby", descriptionId);
  const field = (label, type, value) => {
    const wrapper = document.createElement("label");
    wrapper.className = "profile-field";
    const text = document.createElement("span");
    text.textContent = label;
    const input = document.createElement("input");
    input.type = type;
    input.className = "profile-input";
    input.value = value;
    wrapper.append(text, input);
    form.append(wrapper);
    return input;
  };
  form.append(title, description);
  const name = field(messages.userName, "text", user?.userName || "");
  name.required = true;
  name.maxLength = 80;
  name.autocomplete = "off";
  const colors = document.createElement("fieldset");
  colors.className = "profile-colors";
  const legend = document.createElement("legend");
  legend.textContent = messages.accentColor;
  const presets = document.createElement("div");
  presets.className = "profile-color-presets";
  const palette = [
    ["#0b57d0", messages.colorBlue], ["#00897b", messages.colorTeal],
    ["#388e3c", messages.colorGreen], ["#c46a15", messages.colorOrange],
    ["#7e57c2", messages.colorPurple], ["#5f6368", messages.colorGray],
  ];
  const custom = document.createElement("label");
  custom.className = "profile-custom-color";
  const accent = document.createElement("input");
  accent.type = "color";
  accent.value = user?.accentColor || "#0b57d0";
  accent.setAttribute("aria-label", messages.customColor);
  const customLabel = document.createElement("span");
  customLabel.textContent = messages.customColor;
  const colorValue = document.createElement("span");
  colorValue.className = "profile-color-value";
  custom.append(accent, customLabel, colorValue);
  const swatches = [];
  const renderColor = () => {
    const value = accent.value.toLowerCase();
    for (const swatch of swatches) {
      swatch.setAttribute("aria-pressed", String(swatch.dataset.color === value));
    }
    colorValue.textContent = value.toUpperCase();
  };
  for (const [value, label] of palette) {
    const swatch = document.createElement("button");
    swatch.type = "button";
    swatch.className = "ui-button profile-color-swatch";
    swatch.dataset.color = value;
    swatch.style.setProperty("--profile-swatch", value);
    swatch.title = label;
    swatch.setAttribute("aria-label", label);
    swatch.addEventListener("click", () => {
      accent.value = value;
      renderColor();
    });
    swatches.push(swatch);
    presets.append(swatch);
  }
  accent.addEventListener("input", renderColor);
  accent.addEventListener("change", renderColor);
  renderColor();
  colors.append(legend, presets, custom);
  form.append(colors);
  const status = document.createElement("p");
  status.className = "profile-dialog-status";
  status.setAttribute("role", "status");
  const actions = document.createElement("div");
  actions.className = "profile-actions";
  const cancel = document.createElement("button");
  cancel.type = "button";
  cancel.className = "ui-button profile-button";
  cancel.textContent = messages.cancel;
  cancel.addEventListener("click", () => { if (!pending) dialog.close(); });
  const save = document.createElement("button");
  save.type = "submit";
  save.className = "ui-button profile-button profile-primary";
  save.textContent = mode === "create" ? messages.createAndOpen : messages.save;
  actions.append(cancel, save);
  form.append(status, actions);
  dialog.append(form);
  document.body.append(dialog);
  let pending = false;
  let createdId = null;
  const setPending = value => {
    pending = value;
    dialog.setAttribute("aria-busy", String(value));
    cancel.disabled = value;
    save.disabled = value || Boolean(createdId && !launch);
    for (const control of [name, accent, ...swatches]) {
      control.disabled = value || Boolean(createdId);
    }
  };
  form.addEventListener("submit", async event => {
    event.preventDefault();
    if (pending || (createdId ? !launch : !name.value.trim() || !form.reportValidity())) {
      return;
    }
    setPending(true);
    status.textContent = messages.loading;
    try {
      if (createdId) {
        await launch(createdId);
      } else {
        const value = { userName: name.value.trim(), accentColor: accent.value };
        if (mode === "edit" && current) {
          const latest = await current();
          // Preserve concurrent edits to fields this editor did not change.
          if (value.userName === user.userName) value.userName = latest.userName;
          if (value.accentColor.toLowerCase() === user.accentColor.toLowerCase()) value.accentColor = latest.accentColor;
        }
        const result = await submit(value);
        if (mode === "create" && result?.id && result.launched === false) {
          createdId = result.id;
          cancel.textContent = messages.close;
          save.textContent = messages.retryLaunch;
          status.textContent = messages.createdLaunchFailed;
          setPending(false);
          return;
        }
      }
      dialog.close();
    } catch {
      setPending(false);
      status.textContent = createdId ? messages.createdLaunchFailed : messages.failed;
    }
  });
  dialog.addEventListener("cancel", event => {
    if (pending) {
      event.preventDefault();
    }
  });
  dialog.addEventListener("close", () => {
    dialog.remove();
    if (previousFocus?.isConnected && !previousFocus.closest("[hidden], [inert]")) {
      previousFocus.focus();
    }
  }, { once: true });
  dialog.showModal();
  name.focus();
  return dialog;
}

export async function mountProfileMenu({ container, locale, current, update, create, launch, navigate, dismiss, changed = () => {}, isCurrent = () => true, returnFocus = null }) {
  const document = container.ownerDocument;
  const messages = profileMessages(locale);
  const user = await current();
  if (!container.isConnected || !isCurrent()) {
    return;
  }
  container.replaceChildren();
  const headingRow = document.createElement("div");
  headingRow.className = "profile-menu-identity";
  const avatar = document.createElement("span");
  avatar.className = "profile-menu-avatar";
  const presentation = profileAvatarPresentation(user);
  avatar.textContent = presentation.initial;
  avatar.style.background = presentation.background;
  avatar.style.color = presentation.color;
  avatar.setAttribute("aria-hidden", "true");
  const headingWrap = document.createElement("div");
  headingWrap.className = "profile-menu-text";
  const heading = document.createElement("strong");
  heading.className = "profile-menu-heading";
  heading.textContent = user.userName || messages.unnamed;
  const subtitle = document.createElement("small");
  subtitle.className = "profile-menu-subtitle";
  subtitle.textContent = messages.local;
  headingWrap.append(heading, subtitle);
  const edit = document.createElement("button");
  edit.type = "button";
  edit.className = "ui-icon-button profile-menu-edit";
  edit.title = messages.edit;
  edit.setAttribute("aria-label", messages.edit);
  setIcon(edit, "edit");
  const status = document.createElement("p");
  status.setAttribute("role", "status");
  edit.addEventListener("click", async () => {
    edit.disabled = true;
    try {
      const latest = await current();
      if (!container.isConnected || !isCurrent()) return;
      dismiss();
      showProfileDialog(document, messages, {
        user: latest, current, returnFocus: returnFocus || document.activeElement,
        submit: async value => { await update(value); changed(); },
      });
    } catch {
      if (container.isConnected && isCurrent()) {
        status.textContent = messages.failed;
        container.append(status);
      }
    } finally {
      edit.disabled = false;
    }
  });
  headingRow.append(avatar, headingWrap, edit);
  container.append(headingRow);
  const group = document.createElement("div");
  group.className = "profile-menu-actions";
  const action = (text, icon, handler) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "menu-item profile-menu-item";
    setIcon(button, icon);
    const label = document.createElement("span");
    label.className = "menu-item-label";
    label.textContent = text;
    button.append(label);
    button.addEventListener("click", handler);
    group.append(button);
  };
  action(messages.create, "plus", () => {
    dismiss();
    showProfileDialog(document, messages, {
      mode: "create", submit: create, launch, returnFocus: returnFocus || document.activeElement,
    });
  });
  action(messages.manage, "profile", () => { dismiss(); navigate("navis://profiles/"); });
  container.append(group);
}
