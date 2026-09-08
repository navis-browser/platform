/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { appendNavisMark } from "./brand.mjs";

const SVG_NAMESPACE = "http://www.w3.org/2000/svg";

// Navis-owned 20-DIP line icons. Keeping the geometry here gives every
// Platform surface the same stroke, optical size, and state behavior without
// depending on Gecko theme artwork or a Chromium asset.
const ICONS = Object.freeze({
  profile: [
    ["circle", { cx: "10", cy: "6.5", r: "3" }],
    ["path", { d: "M3.5 17v-1a6.5 6.5 0 0 1 13 0v1" }],
  ],
  back: [
    ["path", { d: "M12.5 4.5 7 10l5.5 5.5" }],
    ["path", { d: "M7.5 10H17" }],
  ],
  forward: [
    ["path", { d: "m7.5 4.5 5.5 5.5-5.5 5.5" }],
    ["path", { d: "M12.5 10H3" }],
  ],
  reload: [
    ["path", { d: "M16.5 4v4.2h-4.2" }],
    ["path", { d: "M16.5 8.2A7 7 0 1 0 17 12" }],
  ],
  stop: [
    [
      "rect",
      {
        x: "5",
        y: "5",
        width: "10",
        height: "10",
        rx: "2",
        class: "icon-fill",
      },
    ],
  ],
  plus: [
    ["path", { d: "M10 4v12" }],
    ["path", { d: "M4 10h12" }],
  ],
  minimize: [["path", { d: "M4 14.5h12" }]],
  maximize: [["rect", { x: "4", y: "4", width: "12", height: "12", rx: "1" }]],
  restore: [
    ["path", { d: "M6.5 6.5V4h9.5v9.5h-2.5" }],
    ["rect", { x: "4", y: "6.5", width: "9.5", height: "9.5", rx: "1" }],
  ],
  overflow: [
    ["circle", { cx: "4.5", cy: "10", r: "1", class: "icon-fill" }],
    ["circle", { cx: "10", cy: "10", r: "1", class: "icon-fill" }],
    ["circle", { cx: "15.5", cy: "10", r: "1", class: "icon-fill" }],
  ],
  close: [
    ["path", { d: "m5 5 10 10" }],
    ["path", { d: "M15 5 5 15" }],
  ],
  downloads: [
    ["path", { d: "M10 3v9" }],
    ["path", { d: "m6.5 8.5 3.5 3.5 3.5-3.5" }],
    ["path", { d: "M4 16.5h12" }],
  ],
  globe: [
    ["circle", { cx: "10", cy: "10", r: "7" }],
    [
      "path",
      { d: "M3 10h14M10 3c2 2 3 4.3 3 7s-1 5-3 7c-2-2-3-4.3-3-7s1-5 3-7Z" },
    ],
  ],
  loading: [
    ["path", { d: "M16 10a6 6 0 1 1-2-4.5" }],
    ["path", { d: "M14 2.8v3.4h3.4" }],
  ],
  siteControls: [
    ["path", { d: "M3 5h5M12 5h5M3 10h9M16 10h1M3 15h2M9 15h8" }],
    ["circle", { cx: "10", cy: "5", r: "2" }],
    ["circle", { cx: "14", cy: "10", r: "2" }],
    ["circle", { cx: "7", cy: "15", r: "2" }],
  ],
  warning: [
    ["path", { d: "M10 3.2 18 17H2L10 3.2Z" }],
    ["path", { d: "M10 7.2v4.6" }],
    ["circle", { cx: "10", cy: "14.4", r: ".7", class: "icon-fill" }],
  ],
  info: [
    ["circle", { cx: "10", cy: "10", r: "7" }],
    ["path", { d: "M10 9v5" }],
    ["circle", { cx: "10", cy: "6.2", r: ".7", class: "icon-fill" }],
  ],
  extension: [
    [
      "path",
      {
        d: "M4 4h4a2 2 0 1 1 4 0h4v4a2 2 0 1 1 0 4v4h-4a2 2 0 1 0-4 0H4v-4a2 2 0 1 1 0-4V4Z",
      },
    ],
  ],
  pin: [
    ["path", { d: "M7 3h6l-1 4 2.5 2.5v1H11v5l-1 2-1-2v-5H5.5v-1L8 7 7 3Z" }],
  ],
  certificate: [
    ["path", { d: "M4 3.5h12v9H4z" }],
    ["path", { d: "M7 6.5h6M7 9.5h4" }],
    ["path", { d: "m8 12.5-1 4 3-1.5 3 1.5-1-4" }],
  ],
  prompt: [["path", { d: "M3.5 4.5h13v9h-7l-3.5 3v-3H3.5v-9Z" }]],
  sharing: [
    ["circle", { cx: "6", cy: "10", r: "2.2", class: "icon-fill" }],
    ["circle", { cx: "14", cy: "5", r: "2.2", class: "icon-fill" }],
    ["circle", { cx: "14", cy: "15", r: "2.2", class: "icon-fill" }],
    ["path", { d: "m8 9 4-2.7M8 11l4 2.7" }],
  ],
  star: [
    [
      "path",
      {
        d: "m10 2.8 2.2 4.5 5 .7-3.6 3.5.9 5-4.5-2.4L5.5 16l.9-5L2.8 8l5-.7L10 2.8Z",
      },
    ],
  ],
  library: [
    ["path", { d: "M4 3.5h10v13H4z" }],
    ["path", { d: "M7 6.5h4M7 9.5h4M7 12.5h3" }],
    ["path", { d: "M14 5.5h2v11H6" }],
  ],
  password: [
    ["circle", { cx: "7", cy: "9", r: "3.5" }],
    ["path", { d: "M10 9h7M14 9v2M16.5 9v2" }],
  ],
  reveal: [
    [
      "path",
      {
        d: "M2.5 10s2.7-4.5 7.5-4.5 7.5 4.5 7.5 4.5-2.7 4.5-7.5 4.5S2.5 10 2.5 10Z",
      },
    ],
    ["circle", { cx: "10", cy: "10", r: "2.2" }],
  ],
  conceal: [
    [
      "path",
      {
        d: "M3 3l14 14M7.2 6A8.3 8.3 0 0 1 10 5.5c4.8 0 7.5 4.5 7.5 4.5a12 12 0 0 1-2.2 2.7M12.2 14.2a8 8 0 0 1-2.2.3C5.2 14.5 2.5 10 2.5 10a12 12 0 0 1 2-2.5",
      },
    ],
  ],
  menu: [
    ["circle", { cx: "10", cy: "4.5", r: "1", class: "icon-fill" }],
    ["circle", { cx: "10", cy: "10", r: "1", class: "icon-fill" }],
    ["circle", { cx: "10", cy: "15.5", r: "1", class: "icon-fill" }],
  ],
  private: [
    ["path", { d: "M3.5 9h13l-2-4H5.5l-2 4Z" }],
    ["circle", { cx: "6.5", cy: "12", r: "2.5" }],
    ["circle", { cx: "13.5", cy: "12", r: "2.5" }],
    ["path", { d: "M9 12h2" }],
  ],
  history: [
    ["path", { d: "M4.5 5.5H2v-2" }],
    ["path", { d: "M3 5a7 7 0 1 1 0 8" }],
    ["path", { d: "M10 6v4l3 2" }],
  ],
  processes: [
    ["rect", { x: "3", y: "3", width: "14", height: "11", rx: "1.5" }],
    ["path", { d: "M6.5 17h7M10 14v3M5.5 9h2l1.5-3 2 5 1.5-2H15" }],
  ],
  developerTools: [
    ["path", { d: "m6.5 6-4 4 4 4M13.5 6l4 4-4 4M11.5 3.5l-3 13" }],
  ],
  folder: [
    ["path", { d: "M2.5 5.5h6l1.5 2h7.5v8h-15z" }],
    ["path", { d: "M2.5 7.5v-3h5l1.5 2" }],
  ],
  edit: [
    ["path", { d: "m4 14.5.7-3.2L13 3l3 3-8.3 8.3-3.7.2Z" }],
    ["path", { d: "m11.5 4.5 3 3" }],
  ],
  trash: [
    ["path", { d: "M4.5 6h11M7 6V3.8h6V6M6 6l.7 10h6.6L14 6" }],
    ["path", { d: "M8.5 8.5v5M11.5 8.5v5" }],
  ],
  up: [["path", { d: "m5 12 5-5 5 5" }]],
  down: [["path", { d: "m5 8 5 5 5-5" }]],
  openPage: [
    ["path", { d: "M8 4H4v12h12v-4" }],
    ["path", { d: "M11 4h5v5" }],
    ["path", { d: "m9 11 7-7" }],
  ],
});

function appendGeometry(ownerDocument, icon, geometry) {
  for (const [elementName, attributes] of geometry) {
    const node = ownerDocument.createElementNS(SVG_NAMESPACE, elementName);
    for (const [attribute, value] of Object.entries(attributes)) {
      node.setAttribute(attribute, value);
    }
    icon.append(node);
  }
}

export function createIcon(ownerDocument, iconName) {
  const geometry = ICONS[iconName];
  if (!geometry && iconName !== "navis") {
    throw new TypeError(`Unknown Navis icon: ${iconName}`);
  }
  const icon = ownerDocument.createElementNS(SVG_NAMESPACE, "svg");
  icon.classList.add("navis-icon");
  icon.setAttribute("viewBox", "0 0 20 20");
  icon.setAttribute("aria-hidden", "true");
  icon.setAttribute("focusable", "false");
  icon.setAttribute("data-icon", iconName);
  if (iconName === "navis") appendNavisMark(ownerDocument, icon);
  else appendGeometry(ownerDocument, icon, geometry);
  return icon;
}

export function setIcon(container, iconName) {
  let icon = [...container.children].find(child =>
    child.classList?.contains("navis-icon")
  );
  if (!icon) {
    icon = createIcon(container.ownerDocument, iconName);
    container.prepend(icon);
    return icon;
  }
  // Session status updates can be frequent; an unchanged brand mark keeps
  // its scoped gradients and filter nodes instead of rebuilding them.
  if (iconName === "navis" && icon.getAttribute("data-icon") === "navis") {
    return icon;
  }
  const geometry = ICONS[iconName];
  if (!geometry && iconName !== "navis") {
    throw new TypeError(`Unknown Navis icon: ${iconName}`);
  }
  icon.replaceChildren();
  icon.setAttribute("viewBox", "0 0 20 20");
  icon.setAttribute("data-icon", iconName);
  if (iconName === "navis") appendNavisMark(container.ownerDocument, icon);
  else appendGeometry(container.ownerDocument, icon, geometry);
  return icon;
}

export function createIconButton(
  ownerDocument,
  { className = "", icon, label }
) {
  const button = ownerDocument.createElement("button");
  button.className = `ui-icon-button ${className}`.trim();
  button.type = "button";
  button.title = label;
  button.setAttribute("aria-label", label);
  button.append(createIcon(ownerDocument, icon));
  return button;
}

/** Remove state layers before a transient surface is hidden and reused. */
export function clearMaterialRipples(root) {
  for (const ripple of root?.querySelectorAll?.(".navis-ripple") ?? []) {
    ripple.remove();
  }
}

/**
 * Installs the shared Material pointer state layer without changing layout.
 *
 * @param {Document} ownerDocument
 */
export function installMaterialRipple(ownerDocument) {
  const selector = [
    ".ui-button",
    ".ui-icon-button:not(.window-control)",
    ".menu-item",
    ".extension-menu-primary",
    ".bookmark-bar-item",
    ".library-primary",
  ].join(",");
  ownerDocument.addEventListener(
    "pointerdown",
    event => {
      if (!event.isTrusted || event.button !== 0) {
        return;
      }
      const target = event
        .composedPath()
        .find(node => node?.matches?.(selector));
      if (
        !target ||
        target.disabled ||
        target.getAttribute("aria-disabled") === "true"
      ) {
        return;
      }
      const bounds = target.getBoundingClientRect();
      const diameter = Math.ceil(Math.hypot(bounds.width, bounds.height) * 2);
      const ripple = ownerDocument.createElement("span");
      ripple.className = "navis-ripple";
      ripple.style.width = `${diameter}px`;
      ripple.style.height = `${diameter}px`;
      ripple.style.left = `${event.clientX - bounds.left - diameter / 2}px`;
      ripple.style.top = `${event.clientY - bounds.top - diameter / 2}px`;
      target.querySelector(":scope > .navis-ripple")?.remove();
      target.append(ripple);
      const ownerWindow = ownerDocument.defaultView;
      const cleanup = () => {
        ownerWindow.clearTimeout(cleanupTimer);
        ripple.remove();
      };
      const cleanupTimer = ownerWindow.setTimeout(cleanup, 600);
      ripple.addEventListener("animationend", cleanup, { once: true });
      ripple.addEventListener("animationcancel", cleanup, { once: true });
    },
    true
  );
}

export function createMenuController(container) {
  const ownerWindow = container?.documentGlobal;
  if (
    container?.namespaceURI !==
      "http://www.mozilla.org/keymaster/gatekeeper/there.is.only.xul" ||
    container.localName !== "menupopup" ||
    typeof container.openPopupAtScreen !== "function"
  ) {
    throw new TypeError("A Navis menu requires a XUL menupopup");
  }
  const ownerDocument = container.ownerDocument;
  let state = null;
  let pendingRequest = null;

  const indexItems = (items, result = new Map()) => {
    for (const item of items) {
      result.set(item.id, item);
      if (Array.isArray(item.children)) {
        indexItems(item.children, result);
      }
    }
    return result;
  };

  const appendItems = (parent, items) => {
    let previousGroup = null;
    for (const item of items) {
      if (item.type === "separator") {
        parent.append(ownerDocument.createXULElement("menuseparator"));
        previousGroup = null;
        continue;
      }
      if (previousGroup !== null && item.group !== previousGroup) {
        parent.append(ownerDocument.createXULElement("menuseparator"));
      }
      previousGroup = item.group;
      const children = Array.isArray(item.children) ? item.children : [];
      if (children.length) {
        const menu = ownerDocument.createXULElement("menu");
        menu.id = `context-menu-command-${item.id}`;
        menu.setAttribute("label", item.label);
        menu.toggleAttribute("disabled", !item.enabled);
        const popup = ownerDocument.createXULElement("menupopup");
        popup.classList.add("navis-context-menu");
        appendItems(popup, children);
        menu.append(popup);
        parent.append(menu);
        continue;
      }
      const menuitem = ownerDocument.createXULElement("menuitem");
      menuitem.id = `context-menu-command-${item.id}`;
      menuitem.setAttribute("label", item.label);
      menuitem.setAttribute("data-command", item.id);
      menuitem.toggleAttribute("disabled", !item.enabled);
      if (item.type === "checkbox" || item.type === "radio") {
        menuitem.setAttribute("type", item.type);
        menuitem.setAttribute("checked", String(Boolean(item.checked)));
      }
      if (item.shortcut) {
        menuitem.setAttribute("acceltext", item.shortcut);
        menuitem.setAttribute("aria-keyshortcuts", item.shortcut);
      }
      parent.append(menuitem);
    }
  };

  const finishDismissal = closing => {
    if (!closing || state !== closing) {
      return false;
    }
    state = null;
    container.replaceChildren();
    if (!closing.commandSelected && closing.notify) {
      closing.onDismiss?.(closing.reason);
    }
    if (!closing.commandSelected && closing.restoreFocus) {
      closing.restoreFocusCallback?.();
    }
    if (pendingRequest) {
      ownerWindow.queueMicrotask(showPendingMenu);
    }
    return true;
  };

  const dismissMenu = (
    reason = "dismissed",
    { notify = true, restoreFocus = true } = {}
  ) => {
    if (!state) {
      if (reason !== "replaced") {
        pendingRequest = null;
      }
      return false;
    }
    const closing = state;
    closing.reason = reason;
    closing.notify = notify;
    closing.restoreFocus = restoreFocus;
    container.hidePopup(true);
    if (container.state === "closed") {
      finishDismissal(closing);
    }
    return true;
  };

  const selectCommand = commandId => {
    const selecting = state;
    const item = selecting?.items.get(commandId);
    if (!selecting || selecting.commandSelected || !item?.enabled) {
      return false;
    }
    selecting.commandSelected = true;
    selecting.onSelect(commandId);
    return true;
  };

  container.addEventListener("command", event => {
    const item = event
      .composedPath()
      .find(node => node?.hasAttribute?.("data-command"));
    selectCommand(item?.getAttribute("data-command"));
  });

  container.addEventListener("popuphidden", event => {
    if (event.target !== container) {
      return;
    }
    if (!finishDismissal(state) && pendingRequest) {
      ownerWindow.queueMicrotask(showPendingMenu);
    }
  });

  function showPendingMenu() {
    if (!pendingRequest || state || container.state !== "closed") {
      return;
    }
    const request = pendingRequest;
    pendingRequest = null;
    state = {
      commandSelected: false,
      items: indexItems(request.items),
      notify: true,
      onDismiss: request.onDismiss,
      onSelect: request.onSelect,
      reason: "dismissed",
      restoreFocus: true,
      restoreFocusCallback: request.restoreFocus,
    };
    container.setAttribute("aria-label", request.label);
    container.setAttribute("label", request.label);
    container.replaceChildren();
    appendItems(container, request.items);

    const x = Number.isFinite(Number(request.x)) ? Number(request.x) : 16;
    const y = Number.isFinite(Number(request.y)) ? Number(request.y) : 16;
    const menuScreenX = Math.round(
      (Number(ownerWindow.mozInnerScreenX) || 0) + x
    );
    const menuScreenY = Math.round(
      (Number(ownerWindow.mozInnerScreenY) || 0) + y
    );
    container.openPopupAtScreen(
      menuScreenX,
      menuScreenY,
      true,
      request.triggerEvent || null
    );
  }

  const showMenu = request => {
    if (
      !Array.isArray(request?.items) ||
      typeof request.onSelect !== "function" ||
      typeof request.label !== "string" ||
      !request.label.trim()
    ) {
      throw new TypeError(
        "A Navis menu requires a label, items and an action handler"
      );
    }
    pendingRequest = request;
    if (state || container.state !== "closed") {
      dismissMenu("replaced", { restoreFocus: false });
      return;
    }
    showPendingMenu();
  };

  return Object.freeze({
    open: showMenu,
    close: dismissMenu,
    get opened() {
      return Boolean(state);
    },
  });
}
