// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
function extract(start, end) {
  const a = source.indexOf(start), b = source.indexOf(end, a);
  assert.ok(a >= 0 && b > a);
  return source.slice(a, b);
}
const labels = extract("const defaultPromptButtonLabel =", "const webAuthnMessage =");
const render = extract("const renderGenericPrompt =", "const renderPrompt =");
for (const locale of ["en-US", "zh-CN"]) {
  const module = await import(`../gecko-chrome/chrome/content/locales/${locale}.mjs`);
  const messages = module[locale === "en-US" ? "EN_US_MESSAGES" : "ZH_CN_MESSAGES"];
  const elements = { promptTitle: {}, promptMessage: {}, promptPanel: { dataset: {} },
    promptActions: { children: [], replaceChildren() { this.children = []; }, append(child) { this.children.push(child); } } };
  const realm = { ...elements, resetPromptInputs() {}, t: (key, values = {}) => {
    assert.equal(typeof messages[key], "string", key);
    return messages[key].replace(/\{(\w+)\}/g, (_, name) => values[name] ?? "");
  }, document: { createElement() { return { classList: { values: new Set(), add(...v) { v.forEach(x => this.values.add(x)); } }, addEventListener() {} }; } } };
  const api = vm.runInNewContext(`${labels}\n${render}\n({renderGenericPrompt,defaultPromptButtonLabel})`, realm);
  for (const kind of ["blocked-redirect", "blocked-popup"]) {
    delete elements.promptPanel.dataset.promptId;
    api.renderGenericPrompt({ id: "request-1", category: "prompt", kind,
      frameOrigin: "https://passport.example.com", destinationOrigin: "https://console.example.com",
      defaultButton: 1, buttons: [{ id: 0, role: "accept" }, { id: 1, role: "cancel" }] });
    assert.ok(elements.promptTitle.textContent);
    assert.ok(elements.promptMessage.textContent.includes("https://passport.example.com"));
    assert.ok(elements.promptMessage.textContent.includes("https://console.example.com"));
    assert.equal(elements.promptActions.children[0].textContent, messages["prompt.allowOnce"]);
    assert.equal(elements.promptActions.children[1].textContent, messages["prompt.keepBlocked"]);
    assert.equal(elements.promptActions.children[0].classList.values.has("primary"), false);
    assert.equal(elements.promptActions.children[1].classList.values.has("primary"), true);
  }
}
console.log("PASS blocked-navigation prompt: both locales, source/destination display, explicit action and blocking default");
