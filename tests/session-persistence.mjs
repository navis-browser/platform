// SPDX-License-Identifier: MPL-2.0
// Exercise the actual shell persistence and close handlers in DOM unload order.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(process.argv[2] || new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const slice = (start, end) => {
  const first = source.indexOf(start);
  const last = source.indexOf(end, first);
  assert.ok(first >= 0 && last > first, `Missing shell section: ${start}`);
  return source.slice(first, last);
};
const persistence = slice("  const saveCurrentSessionState =", "  const updateSessionPresentation =");
const closure = slice("  const selectSession =", "  const showTabContextMenu =");
const unload = source.slice(source.lastIndexOf('  window.addEventListener(\n    "unload",'), source.indexOf("\n}\n\nconst start ="));
assert.ok(unload.includes("runtime.close()"));

function fixture({ privateMode = false, initialContentWindow = false } = {}) {
  const listeners = [];
  const records = new Map();
  const order = ["third", "first", "second"].map((name, index) => ({
    session: { id: name, state: { url: `https://${name}.invalid/`, closed: false } },
    tab: { setAttribute() {}, scrollIntoView() {}, remove() {} },
    panel: { remove() {} }, index,
  }));
  for (const record of order) records.set(record.session.id, record);
  let persisted = [{ url: "https://prior.invalid/", selected: true }];
  let context;
  let released = false;
  const release = () => {
    if (released) return;
    released = true;
    for (const record of order) record.session.close();
  };
  const window = {
    addEventListener(name, listener, options = {}) {
      assert.equal(name, "unload");
      listeners.push({ listener, capture: options.capture === true, once: options.once === true });
    },
    removeEventListener(name, listener, capture = false) {
      const index = listeners.findIndex(entry => entry.listener === listener && entry.capture === capture);
      if (index !== -1) listeners.splice(index, 1);
    },
    cancelAnimationFrame() {}, close() {},
  };
  const document = { removeEventListener() {} };
  // Runtime registers its ordinary unload listener before Platform is ready.
  window.addEventListener("unload", release);
  const runtime = {
    activateSession(session) { assert.ok(!session.state.closed); },
    formatAddressForDisplay: url => url,
    saveSessionState({ sessions, selectedSession }) {
      persisted = sessions.map(session => ({ url: session.state.pendingURL ?? session.state.url, selected: session === selectedSession }));
      return Promise.resolve(true);
    },
    closeDeveloperTools() { return Promise.resolve(); },
    removeAppearanceDelegate() {}, close: release,
  };
  context = vm.createContext({
    findInPage: { sync() {}, forget() {}, select() {} }, pageTranslation: { sync() {}, forget() {}, select() {} }, records, activeRecord: order[1], closingWindow: false,
    privateMode, initialContentWindow, restoringSessions: false,
    sessionPersistenceTimer: null, clearSiteDataTimer: null, clearPasswordsTimer: null,
    fullscreenHintTimer: null, tabWheelResetTimer: null, downloadsRenderFrame: 0, downloadsSeenFrame: 0,
    extensionActionRenderFrame: 0, appearanceDelegate: {}, tabHoverRecord: null,
    clearTimeout() {}, setTimeout() { return 1; },
    downloadsVisibilityObserver: { disconnect() {} },
    bookmarkBarResizeObserver: { disconnect() {} },
    scheduleDownloadsRender() {}, document, linkStatus: { textContent: "", hidden: true },
    window, runtime, console,
    restoreQueue: { activate() {}, remove() {}, close() {} },
    orderedSessionRecords: () => order.filter(record => records.has(record.session.id)),
    closeCompetingTransientSurfaces() {}, addressEditState: { reset() {} },
    renderActiveSession() {}, renderSessionCount() {}, hideTabHoverCard() {},
  });
  vm.runInContext(`${persistence}\n${closure}\n${unload}\n` +
    "globalThis.actions = {persistSessionState, closeSession, selectSession};", context);
  for (const record of order) {
    record.session.close = () => {
      if (record.session.state.closed) return;
      record.session.state.closed = true;
      vm.runInContext(`forgetClosedSession(records.get(${JSON.stringify(record.session.id)}));`, context);
    };
  }
  return {
    order, context,
    saved: () => persisted,
    persist: () => context.actions.persistSessionState({ immediate: true }),
    close(record) { context.actions.closeSession(record); },
    get closed() { return released; },
    unload(target = document) {
      // At-target capture listeners precede non-capture listeners, regardless
      // of registration order, as in the browser DOM event dispatcher.
      for (const capture of [true, false]) {
        if (target !== document && !capture) continue;
        for (const entry of listeners.filter(entry => entry.capture === capture)) {
          if (entry.once) window.removeEventListener("unload", entry.listener, capture);
          entry.listener({ target });
        }
      }
    },
  };
}
{
  const f = fixture();
  f.persist(); const expected = f.saved();
  f.unload({ location: "about:blank toolbox frame" });
  assert.equal(f.closed, false, "A DevTools child-document unload cannot close the browser Runtime");
  f.unload({ location: "toolbox host replacement" });
  assert.equal(f.closed, false);
  assert.deepEqual(f.saved(), expected);
  f.unload();
  assert.equal(f.closed, true, "Ignoring child unloads must not consume the actual window-close listener");
  assert.deepEqual(f.saved(), expected);
}
{
  const f = fixture();
  f.order[0].session.state.pendingURL = f.order[0].session.state.url;
  f.order[0].session.state.url = "about:blank";
  f.persist(); const expected = f.saved(); f.unload();
  assert.deepEqual(f.saved(), expected, "Early window exit retains addresses of unopened tabs");
  assert.equal(f.saved()[0].url, "https://third.invalid/");
}

{
  const f = fixture();
  f.persist();
  const expected = f.saved();
  f.unload();
  assert.deepEqual(f.saved(), expected, "Closing a window must retain every tab, its visual order and the selected tab");
  assert.ok(f.order.every(record => record.session.state.closed), "Runtime still releases every Session");
}
{
  const f = fixture();
  f.close(f.order[1]);
  assert.deepEqual(f.saved(), [
    { url: "https://third.invalid/", selected: false },
    { url: "https://second.invalid/", selected: true },
  ], "Closing one selected tab still removes it from restoration");
  f.unload();
  assert.equal(f.saved().length, 2, "Window teardown must preserve the remaining tabs");
}
for (const options of [{ privateMode: true }, { initialContentWindow: true }]) {
  const f = fixture(options);
  const prior = f.saved();
  f.persist();
  f.unload();
  assert.deepEqual(f.saved(), prior, "Private and content-created windows must not replace the saved normal window");
}
console.log("Window-close persistence, tab order/selection, individual closure and private/content-window checks passed");
