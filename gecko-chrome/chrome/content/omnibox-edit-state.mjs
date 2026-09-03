/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Keep typed text and rejected text separate from the active Session URL.
// A rejected submission must survive focus moving to another process (for
// example an assistive-technology client) until the user edits, cancels, or a
// real navigation/session transition supersedes it.
export class OmniboxEditState {
  #editing = false;
  #rejected = false;

  get editing() {
    return this.#editing;
  }

  get rejected() {
    return this.#rejected;
  }

  get preservesInput() {
    return this.#editing || this.#rejected;
  }

  beginEditing() {
    this.#editing = true;
  }

  updateInput() {
    this.#editing = true;
    this.#rejected = false;
  }

  accept() {
    this.#editing = false;
    this.#rejected = false;
  }

  reject() {
    this.#editing = true;
    this.#rejected = true;
  }

  reset() {
    this.#editing = false;
    this.#rejected = false;
  }

  blur() {
    const shouldRestoreSessionURL = this.#editing && !this.#rejected;
    this.#editing = false;
    return shouldRestoreSessionURL;
  }
}
