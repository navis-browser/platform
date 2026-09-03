/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// This is the sole privileged module-loader edge in Navis Platform code.
// Product modules import this ordinary chrome module instead of reaching into
// Core-private Gecko modules independently.
const DesktopEngine = ChromeUtils.importESModule(
  "resource://gre/modules/DesktopEngine.sys.mjs"
);

export const DESKTOP_EMBEDDER_API_VERSION =
  DesktopEngine.DESKTOP_EMBEDDER_API_VERSION;
export const EngineRuntime = DesktopEngine.EngineRuntime;
export const EngineView = DesktopEngine.EngineView;
export const getDesktopLocaleState = DesktopEngine.getDesktopLocaleState;
