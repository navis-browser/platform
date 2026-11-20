// SPDX-License-Identifier: MPL-2.0
import { setIcon, updateSelectOptions } from "./design-system.mjs";
import { t } from "./localization.mjs";

/** Persistent document content is native; this class owns only the language panel. */
export class PageTranslation {
  #states = new Map();
  #session = null;
  #serial = 0;
  #panel; #source; #target; #status; #translate; #original; #escape; #opened;

  constructor(document, opened) {
    this.#opened = opened;
    this.#panel = document.getElementById("page-translation");
    this.#source = document.getElementById("translation-source");
    this.#target = document.getElementById("translation-target");
    this.#status = document.getElementById("translation-status");
    this.#translate = document.getElementById("translation-submit");
    this.#original = document.getElementById("translation-original");
    this.#escape = document.getElementById("navis-close-translation-key");
    setIcon(document.getElementById("translation-icon"), "globe");
    const close = document.getElementById("translation-close");
    setIcon(close, "close");
    close.addEventListener("click", () => this.close());
    this.#translate.addEventListener("click", () => this.translate());
    this.#original.addEventListener("click", () => this.restore());
    this.#source.addEventListener("change", () => { const state = this.#state(); if (state) { state.source = this.#source.value; state.error = null; this.#render(); } });
    this.#target.addEventListener("change", () => { const state = this.#state(); if (state) { state.target = this.#target.value; state.error = null; this.#render(); } });
    this.#panel.addEventListener("keydown", event => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); this.close(); }
    });
  }

  #state() {
    const session = this.#session;
    if (!session) return null;
    let state = this.#states.get(session);
    if (!state || state.navigationId !== session.state.navigationId || session.state.crashed || session.state.closed) {
      state = { navigationId: session.state.navigationId, open: false, preparing: false, busy: false,
        source: "", target: "", languages: null, error: null };
      this.#states.set(session, state);
    }
    return state;
  }

  select(session) { ++this.#serial; this.#session = session; this.#render(); }
  sync(session) { if (session === this.#session) this.#render(); }
  forget(session) { this.#states.delete(session); if (session === this.#session) this.select(null); }

  #current(session, state, serial) {
    return this.#session === session && this.#state() === state && this.#serial === serial;
  }

  async open() {
    const session = this.#session, state = this.#state();
    if (!state || !session.canTranslatePage) return false;
    const serial = ++this.#serial;
    this.#opened();
    state.open = true; state.preparing = true; state.error = null;
    this.#render();
    this.#panel.focus();
    try {
      const prepared = await session.prepareTranslation();
      if (!this.#current(session, state, serial)) return false;
      state.languages = prepared;
      state.target ||= prepared.targetLanguage;
      state.preparing = false;
      this.#render();
      if (state.open) this.#source.focus();
      return true;
    } catch (error) {
      if (this.#current(session, state, serial)) {
        state.preparing = false;
        if (error.name !== "AbortError") state.error = session.state.translation?.error || "language-load-failure";
        this.#render();
      }
      return false;
    }
  }

  async translate() {
    const session = this.#session, state = this.#state();
    if (!state?.open || state.busy || state.preparing) return;
    if (!state.languages) return this.open();
    const serial = ++this.#serial;
    state.busy = true; state.error = null; this.#render();
    try {
      await session.translatePage({ sourceLanguage: state.source, targetLanguage: state.target });
    } catch (error) {
      if (error.name !== "AbortError" && this.#current(session, state, serial)) {
        state.error = session.state.translation?.error || "engine-load-failure";
      }
    } finally {
      // Closing the panel must not cancel translation or leave a per-tab busy latch.
      state.busy = false;
      if (this.#current(session, state, serial)) this.#render();
    }
  }

  restore() {
    const session = this.#session, state = this.#state();
    if (!state?.open || state.busy || state.preparing) return false;
    try { return session.restoreOriginalPage(); }
    catch (error) { if (error.name !== "AbortError") { state.error = "restore-failure"; this.#render(); } return false; }
  }

  close({ focus = true } = {}) {
    const state = this.#state();
    if (!state?.open) return false;
    ++this.#serial; state.open = false; state.preparing = false;
    this.#render();
    if (focus) this.#session.view?.focus();
    return true;
  }

  #render() {
    const state = this.#state(), open = state?.open === true;
    this.#panel.hidden = !open;
    this.#escape.toggleAttribute("disabled", !open);
    if (!open) return;
    const native = this.#session.state.translation;
    const busy = state.busy || state.preparing || ["downloading", "translating"].includes(native?.phase);
    const names = new Intl.DisplayNames(undefined, { type: "language" });
    const label = id => { try { return names.of(id) || id; } catch { return id; } };
    const detected = native?.detectedLanguage || state.languages?.detectedLanguage;
    const automatic = detected ? t("translate.detected", { language: label(detected) }) : t("translate.auto");
    updateSelectOptions(this.#source, [{ id: "", label: automatic }, ...(state.languages?.sourceLanguages || []).map(id => ({ id, label: label(id) }))]);
    updateSelectOptions(this.#target, (state.languages?.targetLanguages || []).map(id => ({ id, label: label(id) })));
    this.#source.value = state.source; this.#target.value = state.target;
    this.#source.disabled = busy; this.#target.disabled = busy;
    const error = state.error || native?.error;
    const source = state.source || detected;
    this.#translate.disabled = busy || error === "unsupported-device" || Boolean(state.languages && (!source || source === state.target));
    this.#translate.textContent = t(error ? "translate.retry" : "translate.action");
    this.#original.hidden = !native?.sourceLanguage;
    this.#original.disabled = busy;
    this.#panel.setAttribute("aria-busy", String(busy));
    this.#status.setAttribute("role", error ? "alert" : "status");
    this.#status.textContent = error ? t(`translate.error.${error}`) : state.preparing ? t("translate.preparing") :
      native?.phase === "downloading" ? t("translate.downloading", { size: new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(native.downloadBytes / 1_000_000) }) :
      busy ? t("translate.translating") : native?.phase === "translated" ? t("translate.done") : t("translate.choose");
  }
}
