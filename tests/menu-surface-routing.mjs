// SPDX-License-Identifier: MPL-2.0

/* Regression check for controls reparented into the application menu. */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(
  new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url),
  "utf8",
);
const start = source.indexOf("  transientSurfaceForPath =");
const end = source.indexOf("\n  };", start);
assert.ok(start >= 0 && end > start);
const routing = source.slice(start, end + "\n  };".length);
const context = vm.createContext({});
vm.runInContext(`let transientSurfaceForPath; ${routing}`, context);
const route = vm.runInContext("transientSurfaceForPath", context);
const node = id => ({matches: selector => selector.split(",").some(part => part.trim() === `#${id}`)});
for (const id of ["extensions-menu-toggle", "bookmark-current", "menu-history", "menu-downloads", "menu-settings"]) {
  assert.equal(route([node("icon"), node(id), node("app-menu")]), "app-menu", id);
}
for (const [id, parent, expected] of [
  ["manage-extensions", "extensions-menu", "extensions"],
  ["extension-page-action", "extensions-menu", "extensions"],
  ["extension-action", "extension-action-buttons", "extensions"],
  ["download-action", "downloads-panel", "downloads"],
  ["profile-action", "profile-menu", "profile"],
  ["bookmark-item", "bookmark-bar-popup", "bookmark-popup"],
]) assert.equal(route([node(id), node(parent)]), expected);
assert.equal(route([node("extensions-menu-toggle")]), "extensions");
assert.equal(route([node("view-stack")]), null);

// Execute the production capture listeners, menu setters and click handler.
// These focused boundary stubs do not claim native event/ripple rendering.
const listeners = {};
const appMenu = {hidden: false, setAttribute() {}};
const extensionsMenu = {hidden: true};
const cleared = [];
const toggle = {setAttribute() {}, addEventListener(type, listener) { listeners[type] = listener; }};
const fixture = vm.createContext({
  appMenu, extensionsMenu, extensionsMenuToggle: toggle, appMenuToggle: toggle,
  document: {addEventListener(type, listener, capture) { assert.equal(capture, true); listeners[type] = listener; }},
  clearMaterialRipples(surface) { cleared.push(surface); },
  renderExtensionActions() {}, positionAnchoredSurface() {},
  extensionActionList: {querySelector() { return null; }},
});
function section(from, until) {
  const begin = source.indexOf(from);
  const finish = source.indexOf(until, begin);
  assert.ok(begin >= 0 && finish > begin);
  return source.slice(begin, finish);
}
vm.runInContext(`
  let transientSurfaceForPath;
  const closeCompetingTransientSurfaces = exception => {
    if (exception !== "app-menu") setAppMenuOpen(false);
    if (exception !== "extensions") setExtensionsMenuOpen(false);
  };
  ${section("  const setExtensionsMenuOpen =", "\n  runtime.setExtensionActionDelegate")}
  ${section("  const setAppMenuOpen =", "\n  let profileMenuGeneration")}
  ${section("  transientSurfaceForPath =", '\n  window.addEventListener("blur", hideTabHoverCard)')}
  ${section('  extensionsMenuToggle.addEventListener("click",', '\n  document\n    .getElementById("close-extensions-menu")')}
`, fixture);
const event = {isTrusted: true, composedPath: () => [node("extensions-menu-toggle"), node("app-menu")]};
listeners.pointerdown(event);
listeners.focusin(event);
assert.equal(appMenu.hidden, false, "menu survives pointerdown and focus transfer until click");
assert.ok(!cleared.includes(appMenu), "capture phase must not clear the application-menu ripple");
listeners.click();
assert.equal(appMenu.hidden, true);
assert.equal(extensionsMenu.hidden, false, "click opens the requested extensions surface");
