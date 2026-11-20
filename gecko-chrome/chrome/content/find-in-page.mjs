// SPDX-License-Identifier: MPL-2.0
import { setIcon } from "./design-system.mjs";
import { t } from "./localization.mjs";

/** Platform owns only UI state; Session owns search, frames and highlights. */
export class FindInPage {
  #states = new Map();
  #session = null;
  #serial = 0;
  #bar; #input; #count; #previous; #next; #escape; #onError;

  constructor(document, onError) {
    this.#onError = onError;
    this.#bar = document.getElementById("find-in-page");
    this.#input = document.getElementById("find-query");
    this.#count = document.getElementById("find-count");
    this.#previous = document.getElementById("find-previous");
    this.#next = document.getElementById("find-next");
    this.#escape = document.getElementById("navis-close-find-key");
    setIcon(this.#previous, "up");
    setIcon(this.#next, "down");
    const close = document.getElementById("find-close");
    setIcon(close, "close");
    close.addEventListener("click", () => this.close());
    this.#previous.addEventListener("click", () => this.step(true));
    this.#next.addEventListener("click", () => this.step(false));
    this.#input.addEventListener("input", event => {
      if (!event.isComposing) this.#search(this.#input.value);
    });
    this.#input.addEventListener("compositionend", () => this.#search(this.#input.value));
    this.#input.addEventListener("keydown", event => {
      if (event.isComposing) return;
      if (event.key === "Enter") { event.preventDefault(); event.stopPropagation(); this.step(event.shiftKey); }
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); this.close(); }
    });
  }

  #state(session = this.#session) {
    if (!session) return null;
    let state = this.#states.get(session);
    if (!state || state.navigationId !== session.state.navigationId || session.state.crashed || session.state.closed) {
      state = { navigationId: session.state.navigationId, open: false, query: "", result: null };
      this.#states.set(session, state);
    }
    return state;
  }

  select(session) { this.#serial++; this.#session = session; this.#render(); }
  sync(session) { if (session === this.#session) this.#render(); }
  forget(session) {
    this.#states.delete(session);
    if (session === this.#session) this.select(null);
  }

  async open({ again = false, backwards = false } = {}) {
    const session = this.#session, state = this.#state();
    if (!state || session.state.closed || session.state.crashed) return;
    const serial = ++this.#serial;
    try {
      const opened = await session.openFindInPage({ again, backwards });
      if (this.#session !== session || this.#state() !== state || serial !== this.#serial) return;
      if (opened.presentation === "document") { state.open = false; this.#render(); return; }
      const wasOpen = state.open;
      state.open = true;
      if (!again && !wasOpen && opened.selection) state.query = opened.selection;
      this.#render();
      if (!again || !wasOpen) { this.#input.focus(); this.#input.select(); }
      if (state.query) await this.#search(state.query, { again: again && wasOpen, backwards });
    } catch (error) { this.#error(error, session, state); }
  }

  step(backwards) {
    const state = this.#state();
    if (state?.open) return this.#search(state.query, { again: true, backwards });
    return this.open({ again: true, backwards });
  }

  async #search(query, options = {}) {
    const session = this.#session, state = this.#state();
    if (!state?.open) return;
    const serial = ++this.#serial;
    state.query = query;
    state.result = null;
    this.#render();
    try {
      const result = await session.findInPage(query, options);
      if (serial !== this.#serial || this.#session !== session || this.#state() !== state || !state.open) return;
      state.result = result;
      this.#render();
    } catch (error) { this.#error(error, session, state); }
  }

  #error(error, session, state) {
    if (error.name !== "AbortError" && session === this.#session && state === this.#state()) {
      this.#onError(t("find.unavailable"));
    }
  }

  close({ focus = true } = {}) {
    const state = this.#state();
    if (!state?.open) return false;
    ++this.#serial;
    state.open = false;
    state.result = null;
    this.#session.clearFindInPage({ focus });
    this.#render();
    return true;
  }

  #render() {
    const state = this.#state();
    const open = state?.open === true;
    this.#bar.hidden = !open;
    this.#escape.toggleAttribute("disabled", !open);
    if (!open) return;
    if (this.#input.value !== state.query) this.#input.value = state.query;
    const result = state.result;
    this.#count.textContent = !state.query || !result ? "" : result.total === -1
      ? t("find.countLimit", { limit: result.limit })
      : t("find.count", { current: result.current, total: result.total });
    this.#input.setAttribute("aria-invalid", String(Boolean(state.query && result?.total === 0)));
    this.#previous.disabled = this.#next.disabled = !state.query || result?.total === 0;
  }
}
