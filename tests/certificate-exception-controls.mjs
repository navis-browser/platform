// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const part = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
const render = part("    const exception = runtime.getCertificateExceptionState(", "    const sitePermissions =");
const action = part('  revokeCertificateException.addEventListener("click",', '  clearSiteData.addEventListener("click",');

test("site information marks accepted risk, binds the offered Session and handles revocation failures", () => {
  const session = {}, states = [], statuses = [];
  const exception = {available: true, navigationId: 9, fingerprint: "AA", sharedWithRegularBrowsing: false};
  let click;
  const button = {addEventListener(type, listener) {assert.equal(type, "click"); click = listener;}};
  const context = vm.createContext({
    session, activeRecord: {session}, siteCertificateException: null,
    revokeCertificateException: button, siteInfoStatus: {setAttribute() {}}, siteInfoDescription: {},
    t: key => key, console: {error() {}},
    setSiteInfoOpen: value => states.push(value), showTransientStatus: value => statuses.push(value),
    renderSiteInfo: () => states.push("render"),
    runtime: {getCertificateExceptionState: () => exception,
      revokeCertificateExceptionForSession(target, request) {
        assert.equal(target, session); assert.deepEqual(structuredClone(request), exceptionRequest);
      }},
  });
  const exceptionRequest = {navigationId: 9, fingerprint: "AA", sharedWithRegularBrowsing: false};
  vm.runInContext(`{${render}}\n${action}`, context);
  assert.equal(button.hidden, false);
  assert.equal(context.siteInfoStatus.textContent, "siteInfo.certificateRiskAccepted");
  click(); assert.deepEqual(states, [false]);
  context.activeRecord = {session: {}}; click(); assert.equal(states.length, 1);
  context.activeRecord = {session};
  context.runtime.revokeCertificateExceptionForSession = () => {throw new Error("stale");};
  click(); assert.equal(statuses.at(-1), "siteInfo.certificateExceptionFailed");
  assert.equal(states.at(-1), "render");
  exception.sharedWithRegularBrowsing = true;
  vm.runInContext(`{${render}}`, context);
  assert.equal(context.siteInfoDescription.textContent, "siteInfo.certificateExceptionShared");
  exception.available = false;
  vm.runInContext(`{${render}}`, context);
  assert.equal(button.hidden, true); assert.equal(context.siteCertificateException, null);
});
