// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import test from "node:test";

const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const markup = await readFile(new URL("../gecko-chrome/chrome/content/main.xhtml", import.meta.url), "utf8");
function section(first, last) {
  const start = source.indexOf(first), end = source.indexOf(last, start);
  assert.ok(start >= 0 && end > start, first);
  return source.slice(start, end);
}

test("new and reopen tab use distinct native key bindings, including remote-content focus", () => {
  for (const [id, modifiers] of [["navis-new-tab-key", "accel"], ["navis-reopen-tab-key", "accel,shift"]]) {
    const key = markup.match(new RegExp(`<xul:key\\s+id="${id}"[^>]+/>`));
    assert.ok(key, id);
    assert.match(key[0], /key="T"/);
    assert.ok(key[0].includes(`modifiers="${modifiers}"`));
  }
  const handlers = new Map(), calls = [];
  vm.runInNewContext(section('  document.getElementById("navis-new-tab-key")', "  // This is the single keyboard-routing table"), {
    document: { getElementById: id => ({ addEventListener(name, handler) {
      assert.equal(name, "command"); handlers.set(id, handler);
    } }) },
    openNewTab: () => calls.push("new"), reopenClosedTab: () => calls.push("restore"),
  });
  const event = { preventDefault: () => calls.push("prevent") };
  handlers.get("navis-new-tab-key")(event);
  handlers.get("navis-reopen-tab-key")(event);
  assert.deepEqual(calls, ["prevent", "new", "prevent", "restore"]);
  assert.doesNotMatch(section('  window.addEventListener("keydown", (event) => {', "  const renderBrowserFullscreen ="), /key === "t"/, "Keydown must not also dispatch the native binding");
});

function fixture({ privateMode = false, empty = false, failure = false } = {}) {
  const calls = [], views = [], viewPanels = new WeakMap();
  const old = { tab: {} }, restored = { tab: {} }, session = {};
  const context = vm.createContext({
    privateMode, closingWindow: false, viewPanels,
    createView() {
      const view = {}; views.push(view);
      viewPanels.set(view, { remove: () => calls.push("remove-unused-panel") });
      return view;
    },
    runtime: { async restoreClosedTab({ view }) {
      if (failure) throw new Error("Unavailable store");
      if (empty) return null;
      view.session = session;
      return { session, index: 0 };
    } },
    adoptSession(value, options) { assert.equal(value, session); assert.equal(options.activate, false); return restored; },
    orderedSessionRecords: () => [old, restored],
    sessionTabs: { insertBefore(tab, before) { assert.equal(tab, restored.tab); assert.equal(before, old.tab); calls.push("insert"); } },
    synchronizeSessionOrder: () => calls.push("order"),
    selectSession(record, options) { assert.equal(record, restored); assert.equal(options.focusContent, true); calls.push("select"); },
    console: { error() {} }, showTransientStatus: value => calls.push(value),
  });
  vm.runInContext(section("  const reopenClosedTab =", "  const loadAddress =") + "\nglobalThis.reopen = reopenClosedTab;", context);
  return { context, calls, views };
}

test("restoration inserts at its recorded position and focuses the restored tab", async () => {
  const f = fixture(); await f.context.reopen();
  assert.deepEqual(f.calls, ["insert", "order", "select"]);
});

test("empty/error restoration cleans unused views, while private and closing windows are no-ops", async () => {
  for (const options of [{ empty: true }, { failure: true }]) {
    const f = fixture(options); await f.context.reopen();
    assert.ok(f.calls.includes("remove-unused-panel"));
    assert.equal(f.context.viewPanels.has(f.views[0]), false);
    assert.ok(!f.calls.includes("select"));
  }
  for (const privateMode of [false, true]) {
    const f = fixture({ privateMode }); f.context.closingWindow = !privateMode;
    await f.context.reopen(); assert.equal(f.views.length, 0);
  }
});
