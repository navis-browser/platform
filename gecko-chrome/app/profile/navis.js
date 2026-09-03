// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

pref("toolkit.defaultChromeURI", "chrome://navis/content/main.xhtml");
pref("toolkit.defaultChromeFeatures", "chrome,dialog=no,all");
pref("toolkit.singletonWindowType", "navis:browser");

// Keep Gecko's remote-content and Fission boundaries mandatory. Advanced
// users may choose how ordinary websites are allocated through the bounded
// Navis product setting, but cannot move web content into the parent process
// or disable the content sandbox as a side effect.
pref("browser.tabs.remote.autostart", true, locked);
pref("fission.autostart", true, locked);
pref("navis.processIsolation.mode", "full");

// ESR's Linux GTK build defaults the GPU process off as a rollout policy,
// including when the packaged binary runs natively on Wayland. Navis 1.0
// requires process-isolated WebRender/WebGPU, so enable the ordinary feature
// default. Do not use layers.gpu-process.force-enabled: Gecko's blocklist,
// launch-failure handling and software fallback must remain authoritative.
pref("layers.gpu-process.enabled", true);

// Permissions Policy is part of the Navis 1.0 cross-origin security baseline.
// ESR 153 carries the implementation behind disabled-by-default exposure
// preferences, so the product profile deliberately enables both enforcement
// and the standards-facing inspection surface.
pref("dom.security.featurePolicy.header.enabled", true, locked);
pref("dom.security.featurePolicy.webidl.enabled", true, locked);

// Keep the standards-facing WebAuthn stack and native desktop authenticators,
// but never replace them with Gecko's test-only software token in production.
// Related-origin requests retain Gecko's well-known validation and require a
// Navis consent decision; direct attestation is never approved silently.
pref("security.webauth.webauthn", true, locked);
pref("security.webauthn.ctap2", true, locked);
pref("security.webauthn.enable_json_serialization_methods", true, locked);
pref("security.webauthn.enable_conditional_mediation", true, locked);
pref("security.webauth.webauthn_enable_softtoken", false, locked);
pref("security.webauth.webauthn_enable_usbtoken", true, locked);
pref("security.webauthn.always_allow_direct_attestation", false, locked);
pref("security.webauthn.related_origin_requests_mode", 2, locked);
pref("security.webauthn.allow_with_certificate_override", false, locked);

// Retain the pinned ESR implementation source for later product decisions,
// but do not expose browser-mediated device, headset, payment, federated-
// identity or digital-credential APIs without matching Navis permission,
// chooser and lifecycle surfaces. Ordinary forms, OAuth and WebAuthn remain
// independent capabilities.
pref("dom.webmidi.enabled", false, locked);
pref("dom.webmidi.gated", true, locked);
pref("dom.webserial.enabled", false, locked);
pref("dom.webserial.gated", true, locked);
pref("dom.vr.enabled", false, locked);
pref("dom.vr.webxr.enabled", false, locked);
pref("dom.payments.request.enabled", false, locked);
pref("dom.security.credentialmanagement.digital.enabled", false, locked);
pref("dom.security.credentialmanagement.identity.enabled", false, locked);

// Navis delegates location acquisition to the operating system. Never send
// Wi-Fi or IP observations to Gecko's network geolocation provider, including
// as a GeoClue/XDG Portal fallback.
pref("geo.provider.native_only", true, locked);
pref("geo.provider.use_mls", false, locked);
#ifdef MOZ_WIDGET_GTK
pref("geo.provider.use_geoclue", true, locked);
#endif
#ifdef XP_WIN
pref("geo.provider.ms-windows-location", true, locked);
pref("geo.provider.use_winrt", true, locked);
#endif

// Page-created notifications must use the operating-system notification
// center instead of Gecko's XUL fallback.
pref("alerts.useSystemBackend", true, locked);

// Keep script-opened windows behind Gecko's user-activation popup blocker.
// Allowed content windows are always diverted through the owning chrome
// window's nsIBrowserDOMWindow and become EngineRuntime Sessions. Navis owns
// explicit normal/private top-level windows; content cannot instantiate a
// second product chrome or a competing process-wide Delegate owner.
pref("dom.disable_open_during_load", true);
pref("browser.link.open_newwindow", 3);
pref("browser.link.open_newwindow.override.external", -1);
pref("browser.link.open_newwindow.restriction", 0);

// Keep the Navis product token while advertising Firefox-compatible Gecko.
// Without this compatibility token, major sites may serve a legacy fallback.
pref("general.useragent.compatMode.firefox", true);

// The user-facing switch owns Gecko's copy/share, navigation and redirect
// query-stripping preferences. It is intentionally opt-in because cleaning a
// URL can change site behavior; DesktopEngine synchronizes the engine prefs.
pref("navis.cleanLinks.enabled", false);
// Navis 1.0 resolves one bundled policy for navigation, redirects and copied
// links. The provider-neutral envelope can later be populated by a signed
// Mozilla, Navis or equivalent adapter without changing policy consumers.
pref("privacy.query_stripping.product_policy_uri", "chrome://navis/content/clean-links-policy.json", locked);
pref("privacy.query_stripping.use_unified_rules", true, locked);
pref("privacy.query_stripping.remote_settings.enabled", false, locked);

pref("browser.ml.enable", false, locked);
pref("extensions.ml.enabled", false, locked);
pref("extensions.formautofill.useml", false, locked);
pref("places.semanticHistory.featureGate", false, locked);
pref("browser.translations.enable", false, locked);

// Navis accepts only this reviewed subset of Mozilla Remote Settings. The
// matching configure allowlist controls packaged bootstrap data; this locked
// runtime policy also prevents excluded clients from synchronizing or reading
// stale profile data. Test builds bypass it so Gecko's arbitrary-collection
// unit tests can continue to exercise the complete signature implementation.
#ifndef ENABLE_TESTS
pref("services.settings.desktop_embedder_allowed_collections", "blocklists/addons,blocklists/addons-bloomfilters,blocklists/gfx,main/moz-essential-domain-fallbacks,main/password-recipes,main/password-rules,main/url-parser-default-unknown-schemes-interventions,security-state/cert-revocations,security-state/intermediates,security-state/onecrl", locked);
pref("services.settings.server", "https://firefox.settings.services.mozilla.com/v1", locked);
pref("services.settings.preview_enabled", false, locked);
#endif

// The restricted application location bootstraps only IDs in the immutable
// application-owned registry. User extensions are installed into the active
// profile only by the authenticated navis://extensions manager. Directory
// scanning, Web/MIME install, autonomous code updates and native hosts remain
// outside the Navis product surface.
pref("extensions.applicationBuiltins.allowedIds", "uBlock0@raymondhill.net", locked);
pref("xpinstall.enabled", false, locked);
pref("xpinstall.signatures.required", true, locked);
pref("extensions.install.requireBuiltInCerts", true, locked);
pref("extensions.enabledScopes", 4, locked);
pref("extensions.startupScanScopes", 4, locked);
pref("extensions.sideloadScopes", 0, locked);
pref("extensions.autoDisableScopes", 0, locked);
pref("extensions.update.enabled", false, locked);
pref("extensions.update.autoUpdateDefault", false, locked);
pref("extensions.systemAddon.update.enabled", false, locked);
pref("extensions.installDistroAddons", false, locked);
pref("extensions.getAddons.showPane", false, locked);
pref("extensions.backgroundServiceWorker.enabled", false, locked);
// Extension backgrounds and pages must not execute on the browser UI thread.
pref("extensions.webextensions.remote", true, locked);
// Action popups are always extension-owned documents, including for MV2.
pref("extensions.manifestV2.actionsPopupURLRestricted", true, locked);
// Optional capabilities require a Navis-owned, user-visible decision. Navis
// 1.0 does not accept the newer data-collection permission vocabulary.
pref("extensions.webextOptionalPermissionPrompts", true, locked);
pref("extensions.dataCollectionPermissions.enabled", false, locked);

// --disable-webrtc leaves Gecko's fake/shared capture substrate compiled, but
// no real desktop camera backend exists. Do not expose a nonfunctional web
// promise in that explicit minimal build profile.
#ifndef MOZ_WEBRTC
pref("media.navigator.enabled", false, locked);
pref("media.peerconnection.enabled", false, locked);
#endif

pref("security.sandbox.content.level", 6);
pref("security.sandbox.content.write_path_whitelist", "");
pref("security.sandbox.content.read_path_whitelist", "");
pref("security.sandbox.content.syscall_whitelist", "");
pref("security.sandbox.socket.process.level", 2);

// Navis-owned local product preferences. Search suggestions remain disabled;
// only an explicit submitted non-URL term is sent to the selected provider.
pref("navis.bookmarks.bar.visible", true);
pref("browser.startup.homepage", "navis://newtab/");
pref("navis.search.defaultProvider", "google");
pref("navis.platform.locale", "system");
pref("navis.extensions.toolbar.pinned", "[\"uBlock0@raymondhill.net\"]");
pref("dom.forms.datetime.timepicker", true);
// Preserve Gecko's engine-owned recovery behavior while presenting network,
// certificate, HTTPS-only and frame-crash errors as one Navis Material family.
pref("browser.desktop-embedder.material-error-pages", true, locked);
// An empty product-owned support base means error documents omit their
// otherwise inherited Firefox/Mozilla help links. Populate this only after
// Navis owns matching, topic-specific documentation.
pref("app.support.baseURL", "", locked);
// Retain Gecko's ordinary inline spellchecking for multiline Web editors.
// Navis ships a pinned en-US dictionary and no remote dictionary installer.
pref("layout.spellcheckDefault", 1, locked);

// Navis has no telemetry product capability. These locked preferences are
// defense in depth in addition to the compile-time no-observability graph.
pref("datareporting.healthreport.uploadEnabled", false, locked);
pref("toolkit.telemetry.enabled", false, locked);
pref("toolkit.telemetry.unified", false, locked);
pref("toolkit.telemetry.archive.enabled", false, locked);
pref("toolkit.telemetry.shutdownPingSender.enabled", false, locked);
pref("toolkit.telemetry.shutdownPingSender.enabledFirstSession", false, locked);
pref("toolkit.telemetry.firstShutdownPing.enabled", false, locked);
pref("toolkit.telemetry.newProfilePing.enabled", false, locked);
pref("toolkit.telemetry.updatePing.enabled", false, locked);
pref("toolkit.telemetry.server", "", locked);
pref("toolkit.coverage.enabled", false, locked);
pref("toolkit.coverage.opt-out", true, locked);
pref("toolkit.coverage.endpoint.base", "", locked);
