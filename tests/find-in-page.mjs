// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../gecko-chrome/chrome/content/find-in-page.mjs", import.meta.url), "utf8")
  .replace(/^import .*;\n/gm, "").replace("export class FindInPage", "class FindInPage");
function fixture() {
  const calls = [], elements = new Map();
  const document = { getElementById(id) {
    if (!elements.has(id)) elements.set(id, { hidden: true, value: "", attributes: {}, listeners: {},
      setAttribute(k,v) { this.attributes[k] = v; },
      toggleAttribute(k, present) { if (present) this.attributes[k] = ""; else delete this.attributes[k]; },
      addEventListener(k,v) { this.listeners[k] = v; },
      focus() { calls.push(["focus", id]); }, select() { calls.push(["select", id]); } });
    return elements.get(id);
  } };
  const C = vm.runInNewContext(source + "\nFindInPage", { setIcon() {},
    t: (id, values) => id + (values ? JSON.stringify(values) : "") });
  const find = new C(document, message => calls.push(["error", message]));
  const session = {
    state: { navigationId: 1, crashed: false, closed: false },
    async openFindInPage(options) { calls.push(["open", options]); return { presentation: "browser", selection: "中文" }; },
    async findInPage(query, options) { calls.push(["search", query, options]); return { query, current: 1, total: 2, limit: 1000 }; },
    clearFindInPage(options) { calls.push(["clear", options]); },
  };
  find.select(session);
  return { find, session, calls, elements, document };
}
test("selection, live counts, previous/next and Escape use the owning Session", async () => {
  const f = fixture(); await f.find.open();
  assert.equal(f.elements.get("find-in-page").hidden, false);
  // Gecko's global XUL key handler tests attribute presence, including "false".
  assert.equal(Object.hasOwn(f.elements.get("navis-close-find-key").attributes, "disabled"), false);
  assert.equal(f.elements.get("find-query").value, "中文");
  assert.match(f.elements.get("find-count").textContent, /"current":1,"total":2/);
  await f.find.step(true);
  assert.equal(f.calls.at(-1)[2].backwards, true);
  const input = f.elements.get("find-query"); input.value = "composing";
  const count = f.calls.length;
  input.listeners.input({ isComposing: true }); assert.equal(f.calls.length, count);
  input.listeners.keydown({ key: "Escape", preventDefault() {}, stopPropagation() {} });
  assert.equal(f.elements.get("find-in-page").hidden, true);
  assert.equal(Object.hasOwn(f.elements.get("navis-close-find-key").attributes, "disabled"), true);
  assert.equal(f.calls.at(-1)[0], "clear");
});
test("late results cannot replace another tab or a newer navigation", async () => {
  const f = fixture(); await f.find.open();
  let resolve;
  f.session.findInPage = () => new Promise(r => { resolve = r; });
  const pending = f.find.step(false);
  f.session.state.navigationId++; f.find.sync(f.session);
  resolve({ total: 9, current: 9 }); await pending;
  assert.equal(f.elements.get("find-in-page").hidden, true);
  const other = { ...f.session, state: { navigationId: 1 } };
  f.find.select(other); assert.equal(f.elements.get("find-in-page").hidden, true);
  f.find.select(f.session); assert.equal(f.elements.get("find-in-page").hidden, true);
});
test("empty and zero results, count limits and the reader-owned UI remain distinct", async () => {
  const f = fixture();
  f.session.findInPage = async () => ({ current: 0, total: 0, limit: 1000 });
  await f.find.open();
  assert.equal(f.elements.get("find-query").attributes["aria-invalid"], "true");
  assert.equal(f.elements.get("find-next").disabled, true);
  f.session.findInPage = async () => ({ current: 0, total: -1, limit: 1000 });
  await f.find.step(false);
  assert.match(f.elements.get("find-count").textContent, /countLimit/);
  f.find.close(); f.session.openFindInPage = async () => ({ presentation: "document" });
  await f.find.open();
  assert.equal(f.elements.get("find-in-page").hidden, true);
});
test("switching tabs restores only that tab's own query and bar", async () => {
  const f = fixture(); await f.find.open();
  const other = { ...f.session, state: { navigationId: 1 } };
  f.find.select(other); assert.equal(f.elements.get("find-in-page").hidden, true);
  f.find.select(f.session); assert.equal(f.elements.get("find-query").value, "中文");
  assert.equal(f.elements.get("find-in-page").hidden, false);
  f.find.forget(f.session); assert.equal(f.elements.get("find-in-page").hidden, true);
});
