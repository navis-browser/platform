/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import {
  getNavisInternalPages,
  resolveNavisInternalPageURI,
} from "resource://gre/modules/DesktopInternalPages.sys.mjs";
import { AppConstants } from "resource://gre/modules/AppConstants.sys.mjs";
import { getDesktopLocaleState } from "resource://gre/modules/DesktopLocale.sys.mjs";
import { renderNavisInternalPage } from "chrome://navis/content/internal-pages.mjs";

const DYNAMIC_DIAGNOSTIC = Object.freeze({
  messageId: "common.loading",
  dynamic: true,
});

function localizedDiagnostic(messageId) {
  return Object.freeze({ messageId });
}

function safeDiagnostic(read, fallback = "") {
  try {
    const value = read();
    return value === undefined || value === null ? fallback : String(value);
  } catch {
    return fallback;
  }
}

function systemInformation(name) {
  return safeDiagnostic(() => Services.sysinfo.getProperty(name));
}

function formatMemory(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value <= 0) {
    return "";
  }
  const gibibytes = value / 1024 ** 3;
  return `${gibibytes >= 10 ? gibibytes.toFixed(0) : gibibytes.toFixed(1)} GiB`;
}

function operatingSystem() {
  return [
    systemInformation("name"),
    systemInformation("version"),
    systemInformation("build"),
  ]
    .filter(Boolean)
    .join(" ");
}

function proxyMode() {
  const mode = Services.prefs.getIntPref("network.proxy.type", 5);
  return localizedDiagnostic(
    new Map([
      [0, "diagnostics.proxyDirect"],
      [1, "diagnostics.proxyManual"],
      [2, "diagnostics.proxyAutomaticConfiguration"],
      [4, "diagnostics.proxyAutoDetect"],
      [5, "diagnostics.proxySystem"],
    ]).get(mode) || "common.unavailable"
  );
}

function dnsOverHttpsMode() {
  const mode = Services.prefs.getIntPref("network.trr.mode", 0);
  return localizedDiagnostic(
    new Map([
      [0, "diagnostics.dohDefault"],
      [2, "diagnostics.dohIncreased"],
      [3, "diagnostics.dohMaximum"],
      [5, "diagnostics.dohOff"],
    ]).get(mode) || "common.unavailable"
  );
}

function diagnostics() {
  const app = Services.appinfo;
  const locale = getDesktopLocaleState();
  const enabled = localizedDiagnostic("diagnostics.enabled");
  const disabled = localizedDiagnostic("diagnostics.disabled");
  const included = localizedDiagnostic("diagnostics.included");
  const notIncluded = localizedDiagnostic("diagnostics.notIncluded");
  const userAgent = safeDiagnostic(
    () =>
      Cc["@mozilla.org/network/protocol;1?name=http"].getService(
        Ci.nsIHttpProtocolHandler
      ).userAgent
  );
  const systemLocales = safeDiagnostic(() =>
    [
      ...Cc["@mozilla.org/intl/ospreferences;1"].getService(
        Ci.mozIOSPreferences
      ).systemLocales,
    ].join(", ")
  );
  const timeZone = safeDiagnostic(
    () => Intl.DateTimeFormat().resolvedOptions().timeZone
  );
  let effectiveContentSandboxLevel = Services.prefs.getIntPref(
    "security.sandbox.content.level",
    0
  );
  if (AppConstants.MOZ_SANDBOX) {
    try {
      effectiveContentSandboxLevel = Cc[
        "@mozilla.org/sandbox/sandbox-settings;1"
      ].getService(Ci.mozISandboxSettings).effectiveContentSandboxLevel;
    } catch {}
  }
  return Object.freeze({
    application: Object.freeze([
      Object.freeze(["diagnostics.name", app.name]),
      Object.freeze(["diagnostics.version", app.version]),
      Object.freeze(["diagnostics.buildId", app.appBuildID]),
      Object.freeze(["diagnostics.displayLanguage", locale.active]),
      Object.freeze(["diagnostics.sessionUptime", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.userAgent", userAgent]),
    ]),
    engine: Object.freeze([
      Object.freeze(["diagnostics.geckoVersion", app.platformVersion]),
      Object.freeze(["diagnostics.geckoBuildId", app.platformBuildID]),
      Object.freeze(["diagnostics.remoteProcesses", DYNAMIC_DIAGNOSTIC]),
      Object.freeze([
        "diagnostics.maximumContentProcesses",
        app.maxWebProcessCount,
      ]),
      Object.freeze(["diagnostics.socketProcess", DYNAMIC_DIAGNOSTIC]),
      Object.freeze([
        "diagnostics.accessibility",
        app.accessibilityEnabled ? enabled : disabled,
      ]),
    ]),
    graphics: Object.freeze([
      Object.freeze(["diagnostics.compositor", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.hardwareAcceleration", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.gpuProcess", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.graphicsAdapter", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.graphicsDriver", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.windowProtocol", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.devicePixelRatio", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.webgpu", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.graphicsFailures", DYNAMIC_DIAGNOSTIC]),
    ]),
    media: Object.freeze([
      Object.freeze(["diagnostics.audioBackend", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.maximumAudioChannels", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.preferredSampleRate", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.codecSupport", DYNAMIC_DIAGNOSTIC]),
    ]),
    network: Object.freeze([
      Object.freeze(["diagnostics.networkState", DYNAMIC_DIAGNOSTIC]),
      Object.freeze(["diagnostics.proxyMode", proxyMode()]),
      Object.freeze(["diagnostics.dnsOverHttps", dnsOverHttpsMode()]),
      Object.freeze([
        "diagnostics.http3",
        Services.prefs.getBoolPref("network.http.http3.enable", true)
          ? enabled
          : disabled,
      ]),
    ]),
    capabilities: Object.freeze([
      Object.freeze([
        "diagnostics.contentSandbox",
        AppConstants.MOZ_SANDBOX
          ? String(effectiveContentSandboxLevel)
          : notIncluded,
      ]),
      Object.freeze(["diagnostics.privateWindow", DYNAMIC_DIAGNOSTIC]),
      Object.freeze([
        "diagnostics.builtInExtensions",
        AppConstants.MOZ_WEBEXTENSIONS_RUNTIME
          ? DYNAMIC_DIAGNOSTIC
          : notIncluded,
      ]),
      Object.freeze(["diagnostics.webAuthn", included]),
    ]),
    system: Object.freeze([
      Object.freeze([
        "diagnostics.operatingSystem",
        operatingSystem() || app.OS,
      ]),
      Object.freeze(["diagnostics.architecture", app.XPCOMABI]),
      Object.freeze(["diagnostics.widgetToolkit", app.widgetToolkit]),
      Object.freeze([
        "diagnostics.desktopEnvironment",
        safeDiagnostic(() => app.desktopEnvironment),
      ]),
      Object.freeze(["diagnostics.logicalProcessors", DYNAMIC_DIAGNOSTIC]),
      Object.freeze([
        "diagnostics.physicalMemory",
        formatMemory(systemInformation("memsize")),
      ]),
      Object.freeze(["diagnostics.systemLocales", systemLocales]),
      Object.freeze(["diagnostics.timeZone", timeZone]),
    ]),
  });
}

function newInputStreamChannel(uri, loadInfo, body) {
  const stream = Cc["@mozilla.org/io/string-input-stream;1"].createInstance(
    Ci.nsIStringInputStream
  );
  stream.setUTF8Data(body);
  const inputChannel = Cc[
    "@mozilla.org/network/input-stream-channel;1"
  ].createInstance(Ci.nsIInputStreamChannel);
  inputChannel.setURI(uri);
  inputChannel.contentStream = stream;
  const channel = inputChannel.QueryInterface(Ci.nsIChannel);
  channel.loadInfo = loadInfo;
  channel.contentType = "text/html";
  channel.contentCharset = "UTF-8";
  channel.originalURI = uri;
  const owner = Services.scriptSecurityManager.createContentPrincipal(
    uri,
    loadInfo.originAttributes
  );
  channel.owner = owner;
  return channel;
}

export function DesktopNavisProtocolHandler() {}

DesktopNavisProtocolHandler.prototype = {
  scheme: "navis",
  defaultPort: -1,
  protocolFlags:
    Ci.nsIProtocolHandler.URI_DANGEROUS_TO_LOAD |
    Ci.nsIProtocolHandler.URI_IS_LOCAL_RESOURCE |
    Ci.nsIProtocolHandler.URI_IS_POTENTIALLY_TRUSTWORTHY |
    Ci.nsIProtocolHandler.URI_HAS_WEB_EXPOSED_ORIGIN,

  newChannel(uri, loadInfo) {
    const page = resolveNavisInternalPageURI(uri);
    if (!page) {
      throw Components.Exception(
        "Unknown or malformed Navis internal page",
        Cr.NS_ERROR_MALFORMED_URI
      );
    }
    const nonce = Services.uuid
      .generateUUID()
      .toString()
      .replaceAll(/[{}-]/g, "");
    const body = renderNavisInternalPage({
      page,
      pages: getNavisInternalPages(),
      diagnostics: diagnostics(),
      locale: getDesktopLocaleState().active,
      nonce,
    });
    return newInputStreamChannel(uri, loadInfo, body);
  },

  allowPort() {
    return false;
  },

  QueryInterface: ChromeUtils.generateQI(["nsIProtocolHandler"]),
};
