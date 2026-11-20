// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import { test } from "node:test";

const source = await readFile(process.env.NAVIS_UI_TEST_SOURCE || new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const update = source.slice(source.indexOf("  const updateSessionPresentation ="), source.indexOf("  runtime.setSessionDelegateFactory"));

test("Core terminal Session state removes its UI record instead of retaining a dead active tab", () => {
  const session = { id: "closed", state: { closed: true } };
  const record = { session };
  const removed = [];
  const context = vm.createContext({ findInPage: { sync() {}, forget() {}, select() {} }, pageTranslation: { sync() {}, forget() {}, select() {} }, records: new Map([[session.id, record]]),
    closingWindow: false, runtime: { closed: false },
    forgetClosedSession: value => removed.push(value), session, activeRecord: record, renderLinkStatus() {} });
  vm.runInContext(update + "\nupdateSessionPresentation(session);", context);
  assert.deepEqual(removed, [record]);
});

test("Runtime/window teardown does not query closed Runtime state while cancellation still settles", () => {
  for (const closingWindow of [false, true]) {
    const session = { id: "closing" };
    const context = vm.createContext({ closingWindow, runtime: { closed: true }, session,
      records: { get() { assert.fail("Teardown must not render or query Runtime state"); } } });
    vm.runInContext(update + "\nupdateSessionPresentation(session);", context);
    const maps = [new Map(), new Map(), new Map()];
    const settled = [];
    for (const map of maps) map.set("pending", { sessionId: session.id, resolve: result => settled.push(result) });
    const delegates = source.slice(source.indexOf("  runtime.setSessionDelegateFactory"), source.indexOf("  // Native content-window creation"));
    const runtime = { closed: true, capabilities: { mediaCapture: false }, setSessionDelegateFactory(factory) {
      const bag = factory(session);
      for (const key of ["permission", "prompt", "webAuthn"]) bag[key].onCanceled({ id: "pending" });
    } };
    Object.assign(context, { runtime, permissionResolvers: maps[0], promptResolvers: maps[1], webAuthnResolvers: maps[2] });
    vm.runInContext(delegates, context);
    assert.equal(settled.length, 3);
    assert.ok(maps.every(map => map.size === 0));
  }
});

test("terminal projection never invokes user close or DevTools commands on a dead Session", () => {
  const start = source.indexOf("  const forgetClosedSession =");
  assert.notEqual(start, -1);
  const implementation = source.slice(start, source.indexOf("  const closeSession =", start));
  assert.doesNotMatch(implementation, /session\.close\(|closeDeveloperTools/);
  const calls = [];
  const first = { session: { id: "closed", state: { closed: true } }, tab: { remove: () => calls.push("tab") },
    panel: { remove: () => calls.push("panel") } };
  const other = { session: { id: "live", state: { closed: false } } };
  const records = new Map([["closed", first], ["live", other]]);
  const context = vm.createContext({ findInPage: { sync() {}, forget() {}, select() {} }, pageTranslation: { forget() { calls.push("translation"); } }, records, tabHoverRecord: first, activeRecord: first,
    closingWindow: false, restoreQueue: { remove() {} },
    hideTabHoverCard: () => calls.push("hover"), closeCompetingTransientSurfaces: () => {},
    orderedSessionRecords: () => [...records.values()], selectSession: value => calls.push(value),
    renderSessionCount: () => {}, persistSessionState: () => {}, first,
    window: { close: () => calls.push("window") } });
  vm.runInContext(implementation + "\nforgetClosedSession(first); forgetClosedSession(first);", context);
  assert.deepEqual(calls, ["translation", "hover", "tab", "panel", other]);
  assert.equal(records.has("closed"), false);
});
