// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../gecko-chrome/chrome/content/page-translation.mjs", import.meta.url), "utf8")
  .replace(/^import .*;\n/gm, "").replace("export class PageTranslation", "class PageTranslation");
const native = (phase = "original", patch = {}) => ({ phase, sourceLanguage: "", targetLanguage: "en", detectedLanguage: "es", error: null, ...patch });

function fixture() {
  const elements = new Map(), calls = [];
  const document = { getElementById(id) {
    if (!elements.has(id)) elements.set(id, { hidden: true, value: "", listeners: {}, attributes: {},
      addEventListener(key, fn) { this.listeners[key] = fn; },
      setAttribute(key, value) { this.attributes[key] = value; },
      toggleAttribute(key, value) { if (value) this.attributes[key] = ""; else delete this.attributes[key]; },
      focus() { calls.push(["focus", id]); } });
    return elements.get(id);
  } };
  const C = vm.runInNewContext(source + "\nPageTranslation", { Intl,
    t: (id, values) => id + (values ? JSON.stringify(values) : ""),
    setIcon: (_, id) => calls.push(["icon", id]),
    updateSelectOptions(select, values) { select.choices = values; },
  });
  const panel = new C(document, () => calls.push(["close-find"]));
  const session = { canTranslatePage: true,
    state: { navigationId: 1, translation: native() }, view: { focus: () => calls.push(["content-focus"]) },
    async prepareTranslation() { calls.push(["prepare"]); return { ...this.state.translation, sourceLanguages: ["es", "en"], targetLanguages: ["en", "zh-Hans"] }; },
    async translatePage(options) { calls.push(["translate", { ...options }]); this.state.translation = native("translated", { sourceLanguage: "es" }); panel.sync(this); },
    restoreOriginalPage() { calls.push(["restore"]); return true; },
  };
  panel.select(session);
  return { panel, session, calls, elements };
}

test("language choice is explicit, placed in a named panel, and closes Find before opening", async () => {
  const f = fixture(); await f.panel.open();
  assert.equal(f.elements.get("page-translation").hidden, false);
  assert.equal(f.elements.get("translation-source").value, "");
  assert.equal(f.elements.get("translation-target").value, "en");
  assert.match(f.elements.get("translation-source").choices[0].label, /translate.detected/);
  assert.ok(f.calls.some(row => row[0] === "close-find"));
  assert.ok(!f.calls.some(row => row[0] === "translate"));
  await f.panel.translate();
  assert.deepEqual(f.calls.find(row => row[0] === "translate")[1], { sourceLanguage: "", targetLanguage: "en" });
  assert.equal(f.elements.get("translation-status").textContent, "translate.done");
  assert.equal(f.elements.get("translation-original").hidden, false);
  assert.equal(f.panel.restore(), true);
  assert.equal(f.calls.at(-1)[0], "restore");
});

test("manual source and target, disabled equal pairs, loading and retry remain one flow", async () => {
  const f = fixture(); await f.panel.open();
  const from = f.elements.get("translation-source"), to = f.elements.get("translation-target");
  from.value = "en"; from.listeners.change();
  assert.equal(f.elements.get("translation-submit").disabled, true);
  to.value = "zh-Hans"; to.listeners.change();
  assert.equal(f.elements.get("translation-submit").disabled, false);
  f.session.state.translation = native("downloading", { downloadBytes: 25_000_000 }); f.panel.sync(f.session);
  assert.match(f.elements.get("translation-status").textContent, /translate.downloading/);
  assert.equal(f.elements.get("translation-submit").disabled, true);
  f.session.state.translation = native("error", { error: "engine-load-failure" }); f.panel.sync(f.session);
  assert.equal(f.elements.get("translation-status").attributes.role, "alert");
  assert.equal(f.elements.get("translation-submit").textContent, "translate.retry");
  await f.panel.translate();
  assert.deepEqual(f.calls.find(row => row[0] === "translate")[1], { sourceLanguage: "en", targetLanguage: "zh-Hans" });
});

test("closing and Escape hide only the panel; a tab can reopen its native translation", async () => {
  const f = fixture(); await f.panel.open(); await f.panel.translate();
  assert.equal(f.panel.close({ focus: false }), true);
  assert.equal(f.elements.get("page-translation").hidden, true);
  assert.ok(!f.calls.some(row => row[0] === "restore"));
  assert.equal(f.session.state.translation.phase, "translated");
  await f.panel.open();
  assert.equal(f.elements.get("translation-original").hidden, false);
  f.elements.get("page-translation").listeners.keydown({ key: "Escape", preventDefault() {}, stopPropagation() {} });
  assert.equal(f.elements.get("page-translation").hidden, true);
  assert.equal(f.elements.get("navis-close-translation-key").attributes.disabled, "");
  assert.equal(f.calls.at(-1)[0], "content-focus");
});

test("late preparation and translation callbacks cannot reopen another document or tab", async () => {
  const f = fixture(); let resolve;
  f.session.prepareTranslation = () => new Promise(done => { resolve = done; });
  const pending = f.panel.open();
  f.session.state.navigationId++; f.panel.sync(f.session);
  resolve({ ...native(), sourceLanguages: ["es"], targetLanguages: ["en"] });
  await pending;
  assert.equal(f.elements.get("page-translation").hidden, true);
  f.panel.select({ ...f.session, state: { navigationId: 1, translation: native() } });
  assert.equal(f.elements.get("page-translation").hidden, true);
  f.panel.forget(f.session);
});

test("unavailable documents have no panel, and catalog failure can retry without a page reload", async () => {
  const f = fixture(); f.session.canTranslatePage = false;
  assert.equal(await f.panel.open(), false);
  assert.equal(f.elements.get("page-translation").hidden, true);
  f.session.canTranslatePage = true;
  const prepare = f.session.prepareTranslation;
  f.session.prepareTranslation = async () => { throw new Error("catalog"); };
  await f.panel.open();
  assert.equal(f.elements.get("translation-status").textContent, "translate.error.language-load-failure");
  f.session.prepareTranslation = prepare;
  await f.panel.translate();
  assert.equal(f.elements.get("translation-submit").disabled, false);
  assert.ok(!f.calls.some(row => row[0] === "restore"));
});
