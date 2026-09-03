/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

export function setOmniboxValidity(control, message = "") {
  if (
    typeof control?.setCustomValidity !== "function" ||
    typeof control?.setAttribute !== "function" ||
    typeof control?.removeAttribute !== "function"
  ) {
    throw new TypeError(
      "Omnibox control must support native constraint validation"
    );
  }
  if (typeof message !== "string") {
    throw new TypeError("Omnibox validation message must be a string");
  }

  const invalid = message.length > 0;
  control.setCustomValidity(message);
  if (invalid) {
    // ARIA boolean states are token-valued attributes.  An empty attribute,
    // unlike an HTML boolean attribute, does not mean true and therefore does
    // not expose IA2_STATE_INVALID_ENTRY to Windows accessibility clients.
    control.setAttribute("aria-invalid", "true");
  } else {
    control.removeAttribute("aria-invalid");
  }
  return invalid;
}
