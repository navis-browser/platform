// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import { test } from "node:test";

const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const update = source.slice(source.indexOf("  const updateSessionPresentation ="), source.indexOf("  runtime.setSessionDelegateFactory"));

test("Core terminal Session state removes its UI record instead of retaining a dead active tab", () => {
  const session = { id: "closed", state: { closed: true } };
  const record = { session };
  const removed = [];
  const context = vm.createContext({ records: new Map([[session.id, record]]),
    forgetClosedSession: value => removed.push(value), session });
  vm.runInContext(update + "\nupdateSessionPresentation(session);", context);
  assert.deepEqual(removed, [record]);
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
  const context = vm.createContext({ records, tabHoverRecord: first, activeRecord: first,
    hideTabHoverCard: () => calls.push("hover"), closeCompetingTransientSurfaces: () => {},
    orderedSessionRecords: () => [...records.values()], selectSession: value => calls.push(value),
    renderSessionCount: () => {}, persistSessionState: () => {}, first,
    window: { close: () => calls.push("window") } });
  vm.runInContext(implementation + "\nforgetClosedSession(first); forgetClosedSession(first);", context);
  assert.deepEqual(calls, ["hover", "tab", "panel", other]);
  assert.equal(records.has("closed"), false);
});
