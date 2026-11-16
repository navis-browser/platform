// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

// Execute production listeners. Gecko owns the editor's selection changes
// between pointerdown and click; actual pointer ordering needs native replay.
const source = readFileSync(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const start = source.indexOf('  address.addEventListener("pointerdown",');
const end = source.indexOf('  address.addEventListener("focus",', start);
const blurStart = source.indexOf('  address.addEventListener("blur",', end);
const blurEnd = source.indexOf('  back.addEventListener("click",', blurStart);
assert.ok(start >= 0 && end > start && blurStart > end && blurEnd > blurStart);

function fixture(focused = false) {
  const listeners = new Map();
  const address = {
    value: "https://example.test/中文/path", selectionStart: 0, selectionEnd: 0,
    selectionDirection: "none", selectCalls: 0,
    addEventListener(type, callback) { listeners.set(type, callback); },
    select() {
      this.selectCalls++;
      this.selectionStart = 0;
      this.selectionEnd = this.value.length;
      this.selectionDirection = "none";
    },
  };
  const document = { activeElement: focused ? address : null };
  vm.runInNewContext("let selectAddressOnActivationClick = false;\n" + source.slice(start, end) + source.slice(blurStart, blurEnd), {
    address, document, cancelExtensionOmnibox() {},
    addressEditState: { blur() { return false; } },
  }, { timeout: 1000 });
  return {
    address, document,
    send(type, values = {}) { listeners.get(type)?.({ isPrimary: true, button: 0, detail: 1, ...values }); },
    select(start, end, direction = "forward") {
      Object.assign(address, { selectionStart: start, selectionEnd: end, selectionDirection: direction });
    },
  };
}

test("first ordinary single click selects the whole address once", () => {
  const f = fixture();
  f.send("pointerdown");
  f.document.activeElement = f.address;
  f.select(12, 12);
  f.send("click");
  assert.equal(f.address.selectCalls, 1);
  assert.equal(f.address.selectionStart, 0);
  assert.equal(f.address.selectionEnd, f.address.value.length);
  f.select(5, 5);
  f.send("click");
  assert.equal(f.address.selectCalls, 1);
  assert.equal(f.address.selectionStart, 5);
});

for (const direction of ["forward", "backward"]) {
  test(`first activation preserves a native ${direction} drag selection`, () => {
    const f = fixture();
    f.send("pointerdown");
    f.document.activeElement = f.address;
    f.select(8, 20, direction);
    f.send("click");
    assert.equal(f.address.selectCalls, 0);
    assert.deepEqual([f.address.selectionStart, f.address.selectionEnd, f.address.selectionDirection], [8, 20, direction]);
  });
}

test("an already focused address keeps caret placement and selection", () => {
  const f = fixture(true);
  for (const selection of [[7, 7], [4, 16]]) {
    f.send("pointerdown");
    f.select(...selection);
    f.send("click");
    assert.deepEqual([f.address.selectionStart, f.address.selectionEnd], selection);
  }
  assert.equal(f.address.selectCalls, 0);
});

test("double/triple and keyboard clicks do not request activation select-all", () => {
  for (const detail of [0, 2, 3]) {
    const f = fixture();
    f.send("pointerdown");
    f.document.activeElement = f.address;
    f.select(10, 10);
    f.send("click", { detail });
    assert.equal(f.address.selectCalls, 0, `detail=${detail}`);
  }
});

test("secondary buttons and non-primary pointers leave native selection alone", () => {
  for (const values of [{ button: 1 }, { button: 2 }, { isPrimary: false }]) {
    const f = fixture();
    f.send("pointerdown", values);
    f.select(10, 10);
    f.send("click", values);
    assert.equal(f.address.selectCalls, 0);
  }
});

for (const event of ["pointercancel", "blur"]) {
  test(`${event} clears pending activation without breaking the next single click`, () => {
    const f = fixture();
    f.send("pointerdown");
    f.send(event);
    f.select(10, 10);
    f.send("click");
    assert.equal(f.address.selectCalls, 0);
    f.send("pointerdown");
    f.send("click");
    assert.equal(f.address.selectCalls, 1);
  });
}
