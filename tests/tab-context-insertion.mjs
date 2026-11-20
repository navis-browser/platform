// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const section = (first, last) => {
  const start = source.indexOf(first), end = source.indexOf(last, start);
  assert.ok(start >= 0 && end > start, first);
  return source.slice(start, end);
};

function fixture() {
  const records = new Map(), tabs = [], saved = [], focus = [];
  let menu = null, selected = "C", serial = 0;
  function add(id) {
    const record = { session: { id, state: { crashed: false, audio: { audible: false, muted: false } } }, tab: {} };
    Object.defineProperty(record.tab, "nextSibling", { get: () => tabs[tabs.indexOf(record.tab) + 1] ?? null });
    records.set(id, record); tabs.push(record.tab);
    return record;
  }
  const originals = ["A", "B", "C"].map(add);
  function synchronizeSessionOrder() {
    const byTab = new Map([...records.values()].map(record => [record.tab, record]));
    records.clear();
    for (const tab of tabs) {
      const record = byTab.get(tab);
      records.set(record.session.id, record);
    }
    return true;
  }
  const context = vm.createContext({
    DEFAULT_URI: "navis://newtab/", records,
    closingWindow: false, runtime: {closed: false},
    orderedSessionRecords: () => [...records.values()],
    synchronizeSessionOrder,
    sessionTabs: { insertBefore(tab, before) {
      if (tab === before) return;
      tabs.splice(tabs.indexOf(tab), 1);
      const index = before === null ? tabs.length : tabs.indexOf(before);
      assert.ok(index >= 0); tabs.splice(index, 0, tab);
    } },
    createSession(uri = "navis://newtab/", { activate = true } = {}) {
      assert.equal(uri, "navis://newtab/");
      const record = add("N" + ++serial);
      if (activate) select(record);
      return record;
    },
    selectSession: select,
    address: { focus: () => focus.push("focus"), select: () => focus.push("select") },
    openGeckoContextMenu: request => { menu = request; },
    t: key => key,
  });
  function select(record) {
    selected = record.session.id;
    saved.push({ order: [...records.keys()], selected });
  }
  vm.runInContext(section("  const showTabContextMenu =", "  const showOmniboxContextMenu =") +
    section("  const openNewTab =", "  transientSurfaceForPath =") +
    "globalThis.actions = { showTabContextMenu, openNewTab };", context);
  return {
    originals, saved, focus,
    menu(record) { context.actions.showTabContextMenu(record, { clientX: 150, clientY: 16 }); },
    execute() { assert.ok(menu.items.some(item => item.id === "tab-new" && item.enabled)); menu.onSelect("tab-new"); },
    open: () => context.actions.openNewTab(),
    order: () => [...records.keys()], selected: () => selected,
    remove(record) { records.delete(record.session.id); tabs.splice(tabs.indexOf(record.tab), 1); },
    reorder(order) { tabs.splice(0, tabs.length, ...order.map(record => record.tab)); synchronizeSessionOrder(); },
  };
}

test("context New tab inserts after the clicked first, middle or final tab and selects it", () => {
  for (let index = 0; index < 3; index++) {
    const f = fixture();
    f.menu(f.originals[index]); f.execute();
    const expected = ["A", "B", "C"]; expected.splice(index + 1, 0, "N1");
    assert.deepEqual(f.order(), expected);
    assert.equal(f.selected(), "N1");
    assert.deepEqual(f.saved.at(-1), { order: expected, selected: "N1" });
    assert.deepEqual(f.focus, ["focus", "select"]);
  }
});

test("a captured source uses its current visual position after tab reordering", () => {
  const f = fixture(); f.menu(f.originals[0]);
  f.reorder([f.originals[2], f.originals[0], f.originals[1]]); f.execute();
  assert.deepEqual(f.order(), ["C", "A", "N1", "B"]);
});

test("closing the source invalidates its pending New tab action", () => {
  const f = fixture(); f.menu(f.originals[0]); f.remove(f.originals[0]); f.execute();
  assert.deepEqual(f.order(), ["B", "C"]);
  assert.equal(f.selected(), "C");
  assert.deepEqual(f.focus, []);
  assert.deepEqual(f.saved, []);
});

test("untargeted new-tab commands keep appending at the end", () => {
  const f = fixture(); f.open(); f.open();
  assert.deepEqual(f.order(), ["A", "B", "C", "N1", "N2"]);
  assert.equal(f.selected(), "N2");
});
