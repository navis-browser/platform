// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { OmniboxEditState } from "../gecko-chrome/chrome/content/omnibox-edit-state.mjs";

// Run the actual Platform handlers with explicit input/clipboard host peers.
// Native clipboard ownership and editor behavior require packaged replay.
const source = readFileSync(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const handlers = source.slice(source.indexOf("  const copyAddressSelection ="), source.indexOf('  address.addEventListener("input",'));
const canonical = "https://example.test/%E4%B8%AD?q=%E6%96%87";
const display = "https://example.test/中?q=文";
const edit = new OmniboxEditState();
const listeners = new Map();
const writes = [];
const address = {
  value: display, selectionStart: 0, selectionEnd: display.length,
  addEventListener: (type, callback) => listeners.set(type, callback),
  setRangeText(value, start, end) { this.value = this.value.slice(0, start) + value + this.value.slice(end); },
  dispatchEvent(event) { assert.equal(event.inputType, "deleteByCut"); edit.updateInput(); },
};
const runtime = {
  cleanLinksEnabled: false,
  formatAddressForDisplay(value) { return value === canonical ? display : value; },
  copyAddressLink(value) { if (!value.startsWith("https:")) return false; writes.push(value); return true; },
  copyText(value) { writes.push(value); return true; },
};
const record = {session: {state: {url: canonical}}};
vm.runInNewContext(handlers, {address, runtime, addressEditState: edit, activeRecord: record,
  InputEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } }}, {timeout: 1000});
function copy(type = "copy") {
  let prevented = false;
  listeners.get(type)({type, preventDefault: () => prevented = true});
  return prevented;
}
edit.beginEditing();
assert.equal(copy(), true);
assert.equal(writes.pop(), canonical);
runtime.cleanLinksEnabled = true;
assert.equal(copy(), true);
assert.equal(writes.pop(), canonical);
runtime.cleanLinksEnabled = false;
address.selectionStart = display.indexOf("中"); address.selectionEnd = address.selectionStart + 1;
assert.equal(copy(), false);
assert.deepEqual(writes, []);
// Even an edit that recreates the displayed value must remain typed text.
address.selectionStart = 0; address.selectionEnd = display.length;
edit.updateInput();
assert.equal(copy(), false);
edit.reject();
assert.equal(copy(), false);
edit.reset();
// A Session change cannot silently substitute its new URL for old input.
record.session.state.url = "https://other.test/";
assert.equal(copy(), false);
record.session.state.url = canonical;
assert.equal(copy("cut"), true);
assert.equal(writes.pop(), canonical);
assert.equal(address.value, "");
assert.equal(edit.modified, true);
// Internal addresses retain their canonical text without link cleaning.
edit.reset(); record.session.state.url = address.value = "navis://settings/";
address.selectionEnd = address.value.length;
assert.equal(copy(), true);
assert.equal(writes.pop(), "navis://settings/");
console.log("Omnibox clipboard handlers: canonical whole URL, typed/partial/stale text and cut passed.");
