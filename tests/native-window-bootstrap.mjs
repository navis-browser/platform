// SPDX-License-Identifier: MPL-2.0
// Native chrome creation checks for its initial content when the document load
// finishes. A detached dynamic import must not outlive the bootstrap module.
// Run with: node --experimental-vm-modules tests/native-window-bootstrap.mjs
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../gecko-chrome/chrome/content/bootstrap.mjs", import.meta.url), "utf8");
const events = [];
const context = vm.createContext({
  adoptInitialDocument() { events.push("initial-document-adopted"); },
  console,
  dump() {},
  document: { documentElement: { dataset: {} }, getElementById() { return null; } },
});
const main = new vm.SourceTextModule("adoptInitialDocument();", { context });
const bootstrap = new vm.SourceTextModule(source, {
  context,
  importModuleDynamically: async specifier => {
    assert.equal(specifier, "./main.mjs");
    // Model module source becoming available on a later event-loop turn.
    await new Promise(resolve => setImmediate(resolve));
    await main.link(() => assert.fail("Unexpected dependency"));
    await main.evaluate();
    return main;
  },
});
await bootstrap.link(async specifier => {
  assert.equal(specifier, "./main.mjs");
  await new Promise(resolve => setImmediate(resolve));
  return main;
});
await bootstrap.evaluate();
events.push("native-chrome-load-complete");
assert.deepEqual(events, ["initial-document-adopted", "native-chrome-load-complete"],
  "The module dependency graph must initialize native content before chrome load completes");
console.log("Native-window bootstrap ordering passed; packaged native replay remains required");
