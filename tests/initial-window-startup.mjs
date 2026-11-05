// SPDX-License-Identifier: MPL-2.0
// Actual initial-content and presentation phases with a deferred Runtime ready.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(process.argv[2] || new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const start = source.indexOf("  const initialView = createView();");
const ready = source.indexOf("await runtime.ready;");
assert.ok(start > 0 && start < ready,
  "Native initial content must be adopted and activated before awaiting Runtime readiness");
const early = source.slice(start, source.indexOf("  const showTransientStatus =", start));
const lateStart = source.indexOf("  if (initialSession) {", ready);
const late = source.slice(lateStart, source.indexOf('  window.addEventListener(\n    "unload",', lateStart));
assert.ok(early.includes("runtime.activateSession(initialSession)"));
assert.ok(late.includes("adoptSession(initialSession)"));

function fixture({pending = false, urls = [], restored = [], privateMode = false, fail = false} = {}) {
  const views = [], records = [], events = [], listeners = new Map();
  let resolve, reject;
  const ready = new Promise((yes, no) => { resolve = yes; reject = no; });
  const session = {}, viewPanels = new Map();
  const window = {
    closed: false,
    addEventListener(name, listener, options) {
      assert.equal(name, "unload"); assert.equal(options.once, true);
      assert.ok(!listeners.has(name)); listeners.set(name, listener);
    },
    removeEventListener(name, listener) {
      assert.equal(listeners.get(name), listener); listeners.delete(name);
    },
  };
  const runtime = {
    ready, closed: false,
    close() { if (!this.closed) events.push("closed"); this.closed = true; },
    adoptInitialSession({view}) {
      assert.equal(view, views[0]); events.push("adopt");
      if (fail) throw new Error("Native adoption rejected");
      return pending ? session : null;
    },
    activateSession(value) {
      assert.equal(value, session);
      assert.equal(viewPanels.get(views[0]).hidden, false);
      events.push("activate-native");
    },
    getStartupURIs() { events.push("urls"); return urls; },
    async loadSessionState() { events.push("restore"); return restored; },
  };
  const result = vm.runInNewContext(`(async () => {
    let initialContentWindow = false, restoringSessions = true;
    ${early}
    ${late}
    return {initialContentWindow, restoringSessions};
  })()`, {
    runtime, window, privateMode, viewPanels, DEFAULT_URI: "navis://newtab",
    createView() { const view = {}; views.push(view); viewPanels.set(view, {hidden: true}); return view; },
    adoptSession(value) { assert.equal(value, session); events.push("present-native"); },
    createSession(url, options) { const record = {url, options}; records.push(record); return record; },
    selectSession(record) { events.push(["select", records.indexOf(record)]); },
    persistSessionState() { events.push("persist"); },
  });
  return {result, views, records, events, window, runtime, listeners, resolve, reject};
}
{
  const f = fixture({pending: true, urls: ["ignored"], restored: [{url: "ignored"}]});
  assert.deepEqual(f.events, ["adopt", "activate-native"], "Native window admission cannot wait for readiness");
  assert.equal(f.records.length, 0);
  assert.equal(f.listeners.size, 1, "Closing while startup is pending must release Runtime");
  f.resolve();
  const result = await f.result;
  assert.deepEqual(f.events, ["adopt", "activate-native", "present-native"]);
  assert.equal(f.records.length, 0, "Do not load a replacement document");
  assert.equal(f.views.length, 1);
  assert.equal(f.listeners.size, 0, "Normal unload lifecycle takes over after readiness");
  assert.equal(result.initialContentWindow, true);
  assert.equal(result.restoringSessions, false);
}
for (const options of [
  {}, {urls: ["https://a.invalid", "https://b.invalid"]},
  {restored: [{url: "https://a.invalid"}, {url: "https://b.invalid", selected: true}]},
  {privateMode: true, restored: [{url: "private-must-not-restore"}]},
]) {
  const f = fixture(options);
  assert.deepEqual(f.events, ["adopt"]);
  assert.equal(f.records.length, 0, "Ordinary navigation waits for readiness");
  f.resolve();
  const result = await f.result;
  const expected = options.urls || (!options.privateMode && options.restored?.map(row => row.url)) || ["navis://newtab"];
  assert.deepEqual(f.records.map(row => row.url), expected);
  assert.equal(f.views.length, expected.length);
  for (let i = 0; i < expected.length; ++i) assert.equal(f.records[i].options.view, f.views[i]);
  if (options.privateMode) assert.ok(!f.events.includes("restore"));
  assert.equal(result.initialContentWindow, false);
  assert.equal(result.restoringSessions, false);
}
for (const pending of [false, true]) {
  const f = fixture({pending});
  f.window.closed = true;
  f.listeners.get("unload")();
  f.resolve();
  assert.equal(await f.result, undefined);
  assert.ok(f.runtime.closed);
  assert.equal(f.records.length, 0);
  assert.ok(!f.events.includes("present-native"));
  assert.equal(f.listeners.size, 0);
  const failed = fixture({pending});
  failed.reject(new Error("Profile initialization failed"));
  await assert.rejects(failed.result, /Profile initialization failed/);
  assert.ok(failed.runtime.closed);
  assert.equal(failed.listeners.size, 0);
}
await assert.rejects(fixture({pending: true, fail: true}).result, /Native adoption rejected/);
console.log("Initial-window readiness, presentation, cancellation and failure checks passed; no native or visual evidence");
