// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const render = source.slice(source.indexOf("  const renderLinkStatus ="), source.indexOf("  const updateSessionPresentation ="));
const hover = source.slice(source.indexOf("      onLinkHovered:"), source.indexOf("      onInteraction:", source.indexOf("      onLinkHovered:")));

test("active-tab hover displays the entire safely formatted target as text and clears on exit", () => {
  const linkStatus = {hidden: true, textContent: ""}, session = {id: 1}, record = {};
  const context = vm.createContext({linkStatus, session, records: new Map([[1, record]]), activeRecord: record,
    closingWindow: false, runtime: {formatAddressForDisplay: url => url.replace("%E4%B8%AD%E6%96%87", "中文")}});
  vm.runInContext(`${render}\nglobalThis.handler = ({${hover}}).onLinkHovered;`, context);
  const url = "https://example.test/" + "long-path/".repeat(100) + "%E4%B8%AD%E6%96%87?query=<img>#fragment";
  context.handler({url});
  assert.equal(linkStatus.textContent, url.replace("%E4%B8%AD%E6%96%87", "中文"));
  assert.equal(linkStatus.hidden, false);
  context.activeRecord = {};
  context.handler({url: "https://background.test/"});
  assert.ok(linkStatus.textContent.startsWith("https://example.test/"), "Background tabs cannot overwrite status");
  context.activeRecord = record; context.handler({url: ""});
  assert.equal(linkStatus.hidden, true); assert.equal(linkStatus.textContent, "");
  context.closingWindow = true; context.handler({url}); assert.equal(linkStatus.hidden, true);
});
