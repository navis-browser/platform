// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { createNavisLocalizer } from "../gecko-chrome/chrome/content/localization-core.mjs";

const source = await readFile(new URL("../gecko-chrome/chrome/content/main.mjs", import.meta.url), "utf8");
const start = source.indexOf("  const identityPresentation =");
const end = source.indexOf("  const appendCertificateField =", start);
assert.ok(start >= 0 && end > start);

for (const locale of ["en-US", "zh-CN"]) {
  const localizer = createNavisLocalizer(locale);
  const present = vm.runInNewContext(`${source.slice(start, end)}; identityPresentation;`, { t: localizer.text });
  test(`${locale}: file identity labels the toolbar and panel without implying TLS or granting website identity`, () => {
    for (const security of ["unknown", "insecure", "secure"]) {
      const value = present({ identity: "web", identityKey: "local-file", security });
      assert.equal(value.icon, "file");
      assert.equal(value.toolbarLabel, locale === "en-US" ? "Local file" : "本地文件");
      assert.equal(value.toolbarTitle, value.panelTitle);
      assert.ok(value.status && value.description);
    }
    assert.equal(present({ identity: "web", identityKey: "", security: "insecure", url: "file:///fake" }).icon, "warning");
    assert.equal(present({ identity: "web", identityKey: "", security: "broken" }).icon, "warning");
    assert.equal(present({ identity: "web", identityKey: "local-page", security: "insecure" }).icon, "siteControls");
    assert.equal(present({ identity: "internal-page", identityKey: "settings", security: "insecure" }).icon, "shield");
    assert.equal(present({ identity: "built-in-extension", security: "unknown" }).icon, "extension");
    assert.equal(present({ identity: "internal-error", security: "insecure" }).icon, "warning");
  });
}
