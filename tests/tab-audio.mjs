// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(process.env.NAVIS_UI_TEST_SOURCE || new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
function section(first, last) {
  const a = source.indexOf(first), b = source.indexOf(last, a);
  assert.ok(a >= 0 && b > a, first); return source.slice(a, b);
}
const menu = section("  const showTabContextMenu =", "  const showOmniboxContextMenu =");
for (const muted of [false, true]) {
  const calls = [], target = {session: {id: 2, state: {audio: {muted}}, setAudioMuted: value => calls.push(value)}};
  const active = {session: {id: 1, state: {audio: {muted: false}}}};
  const records = new Map([[1, active], [2, target]]);
  let opened;
  const ctx = {records, runtime: {closed: false}, closingWindow: false, t: x => x,
    orderedSessionRecords: () => [...records.values()], openGeckoContextMenu: value => { opened = value; }};
  const show = vm.runInNewContext(`${menu}\nshowTabContextMenu;`, ctx);
  show(target, {clientX: 1, clientY: 1});
  const command = muted ? "tab-unmute" : "tab-mute";
  assert.equal(opened.items.find(item => item.id === command)?.enabled, true);
  assert.equal(opened.items.some(item => item.id === (muted ? "tab-mute" : "tab-unmute")), false);
  opened.onSelect(command); assert.deepEqual(calls, [!muted], "Apply to right-clicked background tab");
  records.delete(2); opened.onSelect(command); assert.equal(calls.length, 1, "Reject stale tab menu");
}
const render = section("  const renderSessionTab =", "  const renderSessionCount =");
function element() { return {classList: {toggle() {}}, attrs: {}, dataset: {}, setAttribute(k,v) {this.attrs[k]=v;}, removeAttribute(k) {delete this.attrs[k];}}; }
for (const audio of [{audible: false, muted: false}, {audible: true, muted: false}, {audible: false, muted: true}]) {
  const record = {session: {state: {audio, title: "Fixture"}}, media: {}, title: {}, tab: element(),
    indicator: {}, favicon: element(), indicatorHost: element(), audioIndicator: element()};
  const draw = vm.runInNewContext(`${render}\nrenderSessionTab;`, {t:x=>x, restoreQueue:{pendingURL(){}}, hasVisibleLoading:()=>false,
    tabHoverRecord:null, setIcon:(node, icon)=>{node.icon=icon;}});
  draw(record);
  assert.equal(record.audioIndicator.hidden, !audio.audible && !audio.muted);
  assert.equal(record.audioIndicator.icon, audio.muted ? "volumeOff" : "volumeOn");
  if (audio.audible || audio.muted) assert.match(record.tab.attrs["aria-label"], /tabs\.(audible|muted)/);
}
console.log("PASS tab audio UI: current mute command, background target/stale menu, audible/muted visibility and accessible labels");
