/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/** Owns one field's query lifetime. Network consent belongs to Core. */
export class OmniboxSuggestions {
  #local;
  #remote;
  #changed;
  #schedule;
  #unschedule;
  #timer = null;
  #request = null;
  #generation = 0;

  constructor({local, remote, changed,
    schedule = (callback, delay) => globalThis.setTimeout(callback, delay),
    unschedule = handle => globalThis.clearTimeout(handle)}) {
    this.#local = local;
    this.#remote = remote;
    this.#changed = changed;
    this.#schedule = schedule;
    this.#unschedule = unschedule;
  }

  clear() {
    this.#generation++;
    this.#request?.abort();
    this.#request = null;
    if (this.#timer !== null) this.#unschedule(this.#timer);
    this.#timer = null;
    this.#changed(Object.freeze({query: "", rows: Object.freeze([])}));
  }

  update(value, {composing = false} = {}) {
    this.clear();
    const query = typeof value === "string" ? value.trim() : "";
    if (composing || !query || query.length > 8192) return;
    const generation = this.#generation;
    const controller = new AbortController();
    this.#request = controller;
    const options = {signal: controller.signal};
    let localRows = [], remoteRows = [];
    const publish = () => {
      if (controller.signal.aborted || generation !== this.#generation) return;
      this.#changed(Object.freeze({query,
        rows: Object.freeze([...localRows, ...remoteRows].slice(0, 10))}));
    };
    publish();
    Promise.resolve().then(() => this.#local(query, options)).then(rows => {
      localRows = Array.isArray(rows) ? rows.slice(0, 6) : [];
      publish();
    }).catch(() => {});
    this.#timer = this.#schedule(() => {
      this.#timer = null;
      if (controller.signal.aborted) return;
      Promise.resolve().then(() => this.#remote(query, options)).then(rows => {
        const seen = new Set([query.normalize("NFKC").toLowerCase()]);
        remoteRows = [];
        for (const raw of Array.isArray(rows) ? rows : []) {
          if (typeof raw !== "string" || !raw.trim() || raw.length > 512) continue;
          const text = raw.trim();
          const key = text.normalize("NFKC").toLowerCase();
          if (seen.has(key)) continue;
          seen.add(key);
          remoteRows.push(Object.freeze({kind: "search", text, title: text}));
          if (remoteRows.length === 5) break;
        }
        publish();
      }).catch(() => {});
    }, 250);
  }
}
