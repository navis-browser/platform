// SPDX-License-Identifier: MPL-2.0

// Native content-window creation waits for chrome load, then requires its
// initial Session to exist. Keep main in this module's dependency graph so
// initialization runs before load completes. main owns initialization errors.
import "./main.mjs";
