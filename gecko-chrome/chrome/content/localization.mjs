/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { createNavisLocalizer } from "./localization-core.mjs";
import { getDesktopLocaleState } from "./platform-api.mjs";

export const localeState = getDesktopLocaleState();
export const localizer = createNavisLocalizer(localeState.active);
export const t = localizer.text;

const SAFE_LOCALIZED_ATTRIBUTES = Object.freeze([
  "aria-label",
  "placeholder",
  "title",
]);

export function localizeDocument(document) {
  document.documentElement.lang = localizer.locale;
  document.documentElement.dir = "ltr";
  for (const element of document.querySelectorAll("[data-l10n-id]")) {
    element.textContent = t(element.dataset.l10nId);
  }
  for (const attribute of SAFE_LOCALIZED_ATTRIBUTES) {
    const datasetName = `l10n${attribute
      .split("-")
      .map(part => part[0].toUpperCase() + part.slice(1))
      .join("")}`;
    for (const element of document.querySelectorAll(
      `[data-l10n-${attribute}]`
    )) {
      element.setAttribute(attribute, t(element.dataset[datasetName]));
    }
  }
  document.documentElement.dataset.l10nReady = "true";
}
