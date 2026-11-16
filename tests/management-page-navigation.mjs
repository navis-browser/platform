// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
function section(first, last) {
  const start = source.indexOf(first), end = source.indexOf(last, start);
  assert.ok(start >= 0 && end > start, first);
  return source.slice(start, end);
}

function fixture({ privateMode = false } = {}) {
  const records = new Map(), loads = [], selections = [], focused = [], handlers = new Map();
  const context = vm.createContext({
    URL, DEFAULT_URI: "navis://newtab/", records, privateMode, closingWindow: false,
    activeRecord: null, addressEditState: { modified: false, rejected: false },
    restoreQueue: { pendingURL: session => session.state.pendingURL ?? null },
    orderedSessionRecords: () => [...records.values()],
    setAppMenuOpen() {}, setLibraryOpen() {}, setExtensionsMenuOpen() {},
    closeCompetingTransientSurfaces() {},
    selectSession(record, { focusContent = false } = {}) {
      context.activeRecord = record;
      selections.push(record);
      if (record.session.state.pendingURL) {
        loads.push({ record, url: record.session.state.pendingURL, restored: true });
        record.session.state.url = record.session.state.pendingURL;
        record.session.state.pendingURL = null;
      }
      if (focusContent) record.session.view.focus();
    },
    createSession(url, { activate = true } = {}) {
      const record = add({ url, identity: "internal-page", identityKey: new URL(url).host });
      loads.push({ record, url, created: true });
      if (activate) context.selectSession(record);
      return record;
    },
    ...Object.fromEntries(["menuHistory", "menuBookmarks", "menuPasswords", "menuDownloads",
      "menuSettings", "menuProcesses", "menuProfiles", "menuSupport", "manageExtensions"].map(name => [name, {
      addEventListener(type, callback) { assert.equal(type, "click"); handlers.set(name, callback); },
    }])),
  });
  function add(state = {}) {
    const record = { session: { id: records.size + 1,
      state: { url: "https://fixture.invalid/form", private: privateMode, identity: "web", identityKey: "",
        canGoBack: false, canGoForward: false, loading: false, crashed: false, closed: false,
        pendingURL: null, failureCode: null, navigationId: 1, ...state },
      view: { focus: () => focused.push(record) },
      loadUri(url) { loads.push({ record, url }); this.state.url = url; },
    } };
    records.set(record.session.id, record);
    return record;
  }
  vm.runInContext([
    section("  const loadProductPage =", "  const printDocument ="),
    section("  menuSettings.addEventListener", "  menuDeveloperTools.addEventListener"),
    section("  manageExtensions.addEventListener", "  const toggleActiveDeveloperTools ="),
    "globalThis.openPage = loadProductPage;",
  ].join("\n"), context);
  return { context, records, loads, selections, focused, handlers, add,
    activate(record) { context.activeRecord = record; },
    open: uri => context.openPage(uri),
  };
}

const routes = { menuHistory: "history", menuBookmarks: "bookmarks", menuPasswords: "passwords",
  menuDownloads: "downloads", menuSettings: "settings", menuProcesses: "processes",
  menuProfiles: "profiles", menuSupport: "support", manageExtensions: "extensions" };

test("every management-menu command preserves the website and selects a new foreground tab", () => {
  for (const [command, page] of Object.entries(routes)) {
    const f = fixture(), original = f.add(); f.activate(original);
    f.handlers.get(command)();
    assert.equal(original.session.state.url, "https://fixture.invalid/form", command);
    assert.equal(f.records.size, 2, command);
    assert.equal(f.context.activeRecord.session.state.url, `navis://${page}/`, command);
    assert.equal(f.focused.at(-1), f.context.activeRecord, command);
  }
});

test("repeated entry selects the existing page and preserves its current subpage without reloading", () => {
  const f = fixture(), original = f.add();
  const settings = f.add({ url: "navis://settings/privacy#exceptions", identity: "internal-page", identityKey: "settings/privacy" });
  f.activate(original); f.open("navis://settings/"); f.open("navis://settings/");
  assert.equal(f.context.activeRecord, settings);
  assert.equal(f.loads.length, 0);
  assert.equal(f.records.size, 2);
  assert.equal(f.focused.at(-1), settings);
});

test("an existing management tab takes precedence over reusing the selected empty new tab", () => {
  const f = fixture(), blank = f.add({ url: "navis://newtab/", identity: "internal-page", identityKey: "newtab" });
  const history = f.add({ url: "navis://history", identity: "internal-page", identityKey: "history" });
  f.activate(blank); f.open("navis://history/");
  assert.equal(f.context.activeRecord, history);
  assert.equal(blank.session.state.url, "navis://newtab/");
  assert.equal(f.loads.length, 0);
});

test("a pristine selected authenticated new tab can be reused", () => {
  const f = fixture(), blank = f.add({ url: "navis://newtab/", identity: "internal-page", identityKey: "newtab" });
  f.activate(blank); f.open("navis://downloads/");
  assert.equal(f.records.size, 1);
  assert.equal(f.context.activeRecord, blank);
  assert.equal(f.loads.length, 1);
  assert.equal(f.focused.at(-1), blank);
});

test("history-bearing, pending, busy, failed, closed and spoofed new tabs are preserved", () => {
  for (const patch of [{canGoBack: true}, {canGoForward: true}, {pendingURL: "https://fixture.invalid/pending"},
    {loading: true}, {failureCode: "failed"}, {crashed: true}, {closed: true},
    {identity: "web"}, {identityKey: "settings"}, {url: "about:blank"}, {private: true}]) {
    const f = fixture(), blank = f.add({ url: "navis://newtab/", identity: "internal-page", identityKey: "newtab", ...patch });
    const before = { ...blank.session.state }; f.activate(blank); f.open("navis://downloads/");
    assert.equal(f.records.size, 2, JSON.stringify(patch));
    assert.deepEqual(blank.session.state, before, JSON.stringify(patch));
  }
});

test("a new-tab address draft or rejected address is not overwritten", () => {
  for (const field of ["modified", "rejected"]) {
    const f = fixture(), blank = f.add({ url: "navis://newtab/", identity: "internal-page", identityKey: "newtab" });
    f.activate(blank); f.context.addressEditState[field] = true; f.open("navis://settings/");
    assert.equal(f.records.size, 2);
    assert.equal(blank.session.state.url, "navis://newtab/");
  }
});

test("a pending management restoration is selected and loaded through existing activation", () => {
  const f = fixture(), original = f.add();
  const pending = f.add({ url: "about:blank", navigationId: 0, pendingURL: "navis://history/" });
  f.activate(original); f.open("navis://history/");
  assert.equal(f.context.activeRecord, pending);
  assert.equal(f.records.size, 2);
  assert.equal(f.loads.length, 1);
  assert.equal(f.loads[0].restored, true);
  assert.equal(original.session.state.url, "https://fixture.invalid/form");
});

test("a pending different destination takes precedence over the previous management URL", () => {
  const f = fixture(), original = f.add(); f.activate(original);
  const leaving = f.add({ url: "navis://settings/", pendingURL: "https://fixture.invalid/next" });
  f.open("navis://settings/");
  assert.equal(f.records.size, 3);
  assert.notEqual(f.context.activeRecord, leaving);
  assert.equal(leaving.session.state.pendingURL, "https://fixture.invalid/next");
});

test("the selected matching manager takes precedence over another matching tab", () => {
  const f = fixture(); f.add({ url: "navis://settings/" });
  const selected = f.add({ url: "navis://settings/appearance" }); f.activate(selected);
  f.open("navis://settings/");
  assert.equal(f.context.activeRecord, selected);
  assert.equal(f.loads.length, 0);
});

test("different windows, privacy contexts, closed/failed tabs and similar URL hosts cannot be reused", () => {
  for (const privateMode of [false, true]) {
    const f = fixture({ privateMode }), original = f.add(); f.activate(original);
    f.add({ url: "navis://settings/", private: !privateMode });
    f.add({ url: "navis://settings/", closed: true });
    f.add({ url: "navis://settings/", crashed: true });
    f.add({ url: "navis://settings/", failureCode: "failed" });
    f.add({ url: "https://settings/" });
    f.add({ url: "navis://settings.example/" });
    f.add({ url: "navis://settings:443/" });
    f.add({ url: "navis://user@settings/" });
    f.add({ url: "invalid" });
    const other = fixture({ privateMode }); other.add({ url: "navis://settings/" });
    f.open("navis://settings/");
    assert.equal(f.records.size, 11);
    assert.equal(f.context.activeRecord.session.state.private, privateMode);
    assert.equal(f.context.activeRecord.session.state.url, "navis://settings/");
    assert.equal(original.session.state.url, "https://fixture.invalid/form");
  }
});

test("opening with no active record works and shutdown does not create or navigate anything", () => {
  const f = fixture(); f.open("navis://settings/");
  assert.equal(f.records.size, 1); assert.equal(f.focused.length, 1);
  const before = f.loads.length; f.context.closingWindow = true; f.open("navis://history/");
  assert.equal(f.records.size, 1); assert.equal(f.loads.length, before);
});
