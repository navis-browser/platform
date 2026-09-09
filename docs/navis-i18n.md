# Navis internationalization

## Release contract

Navis ships every Navis-owned surface in `en-US` and `zh-CN`. This covers the desktop browser frame, menus, bubbles, dialogs, prompts, compact panels, all public `navis://` pages, visible state and error messages, and accessible names/descriptions. Linux and Windows consume the same desktop message catalogues. A missing or malformed translation is a release failure, not an acceptable blank label.

This contract does not make natural-language text part of Navis Core. Core and its Delegates continue to expose stable state, enum/command identifiers, numbers and immutable data. Navis Platform selects and formats the message shown for that state.

Web content is not translated by Navis. Operating-system-owned pickers and consent surfaces follow the operating-system language. The unmodified upstream uBlock Origin dashboard retains its own upstream localization. Website `Accept-Language`, spellcheck dictionaries and future page translation are separate capabilities from the Navis display language.

When Navis supplies explanatory text inside an operating-system surface, such as the reason shown by native password reauthentication, Platform localizes that text before passing it through the Binding. Storage and Core never own an English fallback for it. Website-provided prompt titles, messages, choices and button labels remain website data and are displayed safely without translation.

Navis-owned UI and retained Gecko UI are two resource owners, but they use one process-wide language decision. Navis chrome and `navis://` pages use the two Platform catalogues. Retained upstream surfaces such as page DevTools use Gecko localization resources, so Navis source-pins the official Mozilla `zh-CN` langpack for the same Gecko ESR. That signed XPI is stored in `gecko-chrome/locales/zh-CN`, packaged offline and never fetched at application startup. An ESR update must update and review the langpack as part of the same source change. Package verification compares the packaged XPI's SHA-256 with the source XPI's SHA-256, preventing repacking or substitution after review.

## Locale selection

The persisted display-language setting has exactly three selection values:

- `system`, which is the default and resolves the operating-system locale;
- `en-US`; and
- `zh-CN`.

`en-US` is the product baseline and final fallback. `system` is a selection mode, not a third shipped language: Platform reads Gecko's normalized `mozIOSPreferences.systemLocale` instead of parsing environment variables or duplicating operating-system detection. Chinese system locale tags resolve to `zh-CN`; every unsupported or malformed locale falls back to `en-US`. Locale identifiers are canonical BCP 47 tags.

The effective locale is frozen for the process only after Gecko publishes `profile-after-change`, when persisted user preferences are available. `initializeDesktopLocale()` resolves the Navis selection once and assigns that same value to Gecko's LocaleService through `Services.locale.requestedLocales`. The packaged langpack becomes available during AddonManager startup, and LocaleService renegotiates before a retained upstream surface such as DevTools can load. An idempotent `final-ui-startup` fallback covers nonstandard startup paths; an earlier read may resolve the current value but cannot freeze it. Navis chrome, internal pages and retained Gecko UI therefore cannot independently choose languages or drift into a mixed-language process.

Changing the setting persists the next process choice immediately; it does not hot-swap only the current document. When the selected language differs from the active language, Settings exposes a `Relaunch` action directly below the selector. It requests Gecko's normal attempt-quit plus restart path, so unsaved-page confirmation can cancel it and Navis session restoration remains in effect. After a successful relaunch, both Navis-owned catalogues and LocaleService use the newly selected language. This is a one-click application relaunch, not a partial reload or an unsafe mixture of old and new resources.

The selected setting and effective locale are profile state owned by Desktop Platform. They do not enter Session restoration, browsing history, a website principal or Navis Core.

Navis chrome obtains that state through the sole public `DesktopEngine.sys.mjs` import, mediated by `platform-api.mjs`. No Platform UI module imports the Core-private locale selector directly.

## Resource and formatting rules

Desktop messages live in two explicit, reviewable Platform catalogues with stable semantic IDs. The `en-US` catalogue is the complete fallback and `zh-CN` must have exact key and placeholder parity. Runtime code may not use an English source sentence as a lookup key.

The common formatter owns:

- placeholder validation and substitution;
- locale-aware plural selection;
- number, date and time formatting;
- the document `lang` and direction metadata; and
- text/attribute application without parsing translated markup.

Catalogues contain text, not executable code or HTML. Navis chrome writes translations through `textContent` or bounded attributes. The internal-page renderer HTML-escapes every localized value and serializes only a bounded client-message subset into its nonce-authenticated script. Diagnostic and Delegate inputs remain data and are never treated as translation keys.

Gecko-generated presentation strings for semantic browser decisions do not cross that boundary. In particular, a `before-unload` descriptor retains its origin, button roles, default/cancel choice and modal scope, while Navis Platform owns the localized title, explanation and action labels. Stable `prompt-action-*` element identifiers expose those semantic choices to both desktop accessibility APIs and language-independent release automation.

## Fallback and failure policy

An unknown requested locale resolves to `en-US`. An intentionally omitted `zh-CN` message may use the verified `en-US` fallback only while developing; catalogue parity blocks packaging and release. Unknown message IDs, missing placeholders, extra placeholders and invalid plural records fail unit/source gates. Product startup keeps a bounded English fatal-error fallback so a catalogue defect cannot leave a blank window. The normal browser frame remains hidden until its catalogue has been applied, preventing a Chinese process from briefly exposing the declarative English fallback labels.

## Acceptance

The Linux and Windows release matrices must each prove:

1. system-locale resolution plus unsupported-locale fallback;
2. explicit `en-US` and `zh-CN` persistence across restart;
3. exact catalogue key/placeholder parity and no empty messages;
4. localized browser chrome, native Gecko context-menu presentation and every public `navis://` page;
5. localized dynamic counts, loading/success/error/disabled states, prompts and accessibility names;
6. locale-aware date, time and number presentation;
7. no translated HTML/script injection and no message IDs visible to users;
8. representative light/dark/private, DPI and constrained-width screenshots in both languages, including long Chinese strings without clipping; and
9. a relaunch-required language change with no mixed-language process state, including cancellation by an unsaved page and one-click application restart.

The bilingual gate augments rather than replaces the existing keyboard, accessibility, native-window and target-site regressions.
