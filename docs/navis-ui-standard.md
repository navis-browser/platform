# Navis UI standard

## Decision

Navis uses one Material Design language across the complete product. The concrete desktop reference is the default horizontal-tab presentation of Google Chrome Stable 152.0.7977.64 on Linux and Windows. This reference fixes visual hierarchy, density, component states and ordinary interaction; it does not import Chrome's product scope, branding or services.

The reference is frozen for Navis:

- [Chrome 152 Stable release](https://chromereleases.googleblog.com/2026/08/stable-channel-update-for-desktop_0256176589.html)
- [Chromium source tag 152.0.7977.64](https://chromium.googlesource.com/chromium/src/+/refs/tags/152.0.7977.64/)

An upstream Chrome release does not silently change the Navis design. Changing the reference requires a deliberate standard revision and a complete visual-regression review.

## Authority and reference order

When requirements appear to conflict, use this order:

1. the Navis capability matrix, architecture and security invariants decide which capabilities and decisions may exist;
2. this document decides the common Navis visual and interaction language;
3. the pinned Chrome executable is the observable UI reference;
4. the matching pinned Chromium source is the implementation-detail reference;
5. general Material Design guidance fills a gap that Chrome and Chromium do not resolve; and
6. native platform convention governs only a surface explicitly owned by the operating system.

Chrome is the reference product, not a second requirements backlog. A Chrome feature that is absent or excluded in the Navis capability matrix does not enter Navis merely because its control is visible in the reference browser.

## Ownership boundary

The Material/Chrome standard applies to every Navis-owned surface, including:

- window chrome, tab strip, toolbar and omnibox;
- menus, bubbles, popovers, dialogs, snackbars and loading feedback;
- permission, authentication, password-save, download and crash surfaces;
- new-tab, history, bookmarks, passwords, settings and data-clearing pages;
- the `navis://urls` index, `navis://support` diagnostics and every other Navis-owned `navis://` document;
- normal, private, offline, empty, error and recovery states; and
- all hover, pressed, selected, focused, disabled and progress states.

Operating-system-owned surfaces retain their native presentation. These include the Linux/Windows file and save pickers, Windows system location consent, Linux desktop-portal consent and taskbar/dock integration. The top-level window remains an OS window with native move, resize, state and close semantics, but Navis owns an integrated tab-strip frame and does not place a second system title bar above it. A native surface must return to a coherent Material Navis state rather than leaving the product with an unstyled intermediate panel.

Web content and the unmodified upstream uBlock Origin dashboard are not Navis UI and are not restyled.

Default Web form-control semantics remain Gecko-owned content behavior. Browser-owned popup presentation for those controls is still user-visible: a future visual translation may skin Gecko's trusted Select/Date picker documents toward the pinned Chrome reference, but it must not replace Gecko value/event/focus/accessibility behavior with a page or Platform overlay.

## Product identity and imitation boundary

Navis follows Chrome's desktop layout, density, state communication and interaction patterns as closely as the Navis architecture permits. It does not use the Chrome logo, Google name, Google account identity, proprietary artwork or wording that implies Google authorship or endorsement.

Navis keeps its own:

- name, application icon and brand marks;
- product accent palette within the Material token system;
- capability-accurate labels and help text; and
- settings and menu contents derived from the Navis capability matrix.

Icons copied or adapted from an external source require recorded provenance and compatible licensing. A visually similar open icon does not authorize use of a Chrome trademark asset.

## Desktop reference scope

The default is Chrome-like horizontal tabs. Vertical tabs, split view, tab groups, reading mode, Gemini, Google account surfaces, cross-device UI and toolbar customization are not implied by the reference. They require their own capability decision.

The surface mapping is:

| Navis surface | Chrome-reference behavior | Navis boundary |
| --- | --- | --- |
| Tab strip | Active/inactive contrast, favicon/title/close affordances, new-tab affordance, bounded overflow and loading state | Session state comes only from Core; no tab group or vertical-tab promise. Closing the sole remaining tab closes its current Platform window. |
| Toolbar | Back, forward, reload/stop, omnibox and compact trailing actions | Only actions backed by the capability matrix appear. |
| Omnibox | Chrome-like shape, focus treatment, security identity placement, URL editing and keyboard flow | Shared search-provider behavior and explicit, default-off suggestion consent follow navis-search-service.md; source checks do not establish native visual or interaction acceptance. |
| Site information | Chrome-like anchored bubble and certificate-details hierarchy | HTTPS, Navis internal page, built-in extension, HTTP and error identities are visibly and accessibly distinct; a Navis mark never claims remote TLS authentication. |
| Bookmark action | Chrome-like star/action feedback and transient confirmation | Backs the Navis local bookmark store, not Google Sync. |
| Downloads | Toolbar progress/status and a compact list surface | Backs the Core download contract; no Safe Browsing reputation claim. |
| Context menus | Chrome-density grouping, disabled state, shortcut column, hover/focus and edge placement | One Material-skinned Gecko `menupopup` owns Web, tab and omnibox menus; Gecko retains lifecycle, keyboard and accessibility semantics. |
| Default Web form pickers | Chrome is the visual reference for browser-owned popup density and state | Gecko retains element semantics and trusted popup ownership; Select/Date must never become page-emulated controls. |
| Extensions | Chrome-like toolbar actions and popups plus a standalone Material extension manager | Built-ins and user-installed extensions use the same Platform surfaces. uBlock Origin is required and pinned by default, but does not receive a separate Navis blocker bubble or private product API. |
| Permissions/prompts | Origin-bound Material bubble or modal, explicit primary/secondary actions and visible dismissal | Unsupported capabilities never receive a misleading permission surface. |
| Password manager | Save/update prompt plus Material internal management surface | Local-only storage; no account or cross-device sync. |
| History/bookmarks/settings | Chrome-like desktop information architecture, lists, search, side navigation and dialogs | Pages contain only Navis-owned settings and capabilities. |
| Private mode | Clearly distinct but related Material color treatment and persistent private-state indication | Private Sessions never enter normal history, storage or restore state. |
| Error/crash state | Designed page with explanation, recovery action and stable navigation chrome | A blank content area is never an acceptable error presentation. |

Navis-owned internal pages use `navis://<page>` and the common internal-page shell described in `navis-internal-pages.md`. They do not expose Firefox `about:` product names or Firefox page styling. `navis://urls` is the visible, generated index of public pages in the current build. The unmodified uBlock Origin dashboard remains extension-owned UI and retains its own identity and presentation.

Safe Browsing, Web Push and EME/Widevine are explicit exclusions. Navis must not retain empty Chrome-like controls, settings or warning text for them.

## Required browser-shell behavior

The following behaviors are part of the acceptance contract:

1. Anchored bubbles, menus and other transient browser popups are mutually exclusive. A click or context-menu action in content, a tab switch, navigation, focus transfer or another popup closes an unrelated transient surface. Persistent side panels are not classified as transient popups.
2. The site-information surface treats authenticated `localhost`, `127.0.0.0/8` and IPv6 loopback as a trustworthy local page and explains: “This page is stored on your computer.” This identity is principal/URI-derived and cannot be obtained by display-string spoofing.
3. The tab strip is integrated into the browser frame; there is no separate system title bar above it. Native window behavior remains intact, and the new-tab affordance follows immediately after the final visible tab.
4. Select, Date, Time and comparable browser-owned anchored popups close when their content anchor scrolls, navigates, disappears or otherwise loses its interaction context.
5. Gecko-owned form popup and media-control presentation, including video controls, is translated toward the frozen Chrome reference without replacing Gecko value, event, media, focus or accessibility semantics.
6. The active tab connects visually to the omnibox toolbar using normal upper radii and outward lower shoulders. It is not an isolated four-corner pill. A leading gutter at least as wide as the lower shoulder keeps the first tab clear of native rounded-window clipping, and an equivalent trailing gutter prevents clipping at the other end. Every tab uses matching outward shoulders on both sides for its highlighted or hover surface. A bounded hover card presents exactly the page title, registrable/main domain and approximate page memory use in that top-to-bottom order. The memory figure is a best-effort allocation of Gecko content-process memory, not a claim of exact per-document accounting.
7. History, bookmarks and passwords have both compact surfaces and dedicated `navis://history`, `navis://bookmarks` and `navis://passwords` pages.
8. Disabled menu commands have visibly reduced text/icon emphasis in every supported theme and cannot be activated.
9. Context-menu clipping, background, border and shadow share the same rounded outline; elevation may not reveal a rectangular backing box.
10. Navis-owned history, bookmarks, passwords, downloads and comparable panels provide applicable text selection, Copy and Select all context actions.
11. Web content is presented as a rounded surface within the frame where space permits; opening a side panel keeps a clear rounded boundary between page and panel while maintaining correct View geometry.
12. A show/hide bookmark bar presents the durable local bookmark hierarchy, including empty, folder, overflow and context-menu states.
13. Site information includes a current-site cookies/site-data summary and bounded clear action authenticated against the actual current origin.
14. `navis://newtab` is the Navis-owned default home/new-Session page. Startup uses it unless normal Session restoration or an explicit user startup choice takes precedence; no network example URL is a built-in default.
15. Settings links to history, bookmarks, passwords, downloads and About; manages the bounded local omnibox search-provider list/default; and links About/help to `navis://support` for diagnostics without inventing an update channel. It also explains and selects the three bounded site-isolation modes, marks Full as the default/recommended mode and requires relaunch to apply a changed process allocation policy. About does not duplicate this user choice as a static “process model” diagnostic.

For this document, “omnibox toolbar” means the row containing navigation controls and the address/search field. The tab strip is the row above it, and the requested active-tab shoulders visually bridge those two rows.

## Design-system implementation

The desktop implementation remains privileged XHTML, CSS and JavaScript above the public Core contract. It does not adopt Chromium Views, Aura, Skia or Chromium browser services as dependencies.

Navis Platform must provide one internal design-system layer containing:

- semantic color tokens for surface, container, text, icon, outline, accent, danger, warning and success roles in light, dark, private and high-contrast modes;
- typography roles rather than page-local font sizes and weights;
- spacing, control-size, corner-radius, elevation, focus-ring and motion tokens derived from the pinned Chrome desktop reference;
- shared icons with normal, hover, pressed, disabled and selected states; and
- reusable tab, icon-button, text-field, menu, menu-item, bubble, dialog, snackbar, progress, switch, checkbox, list-row and internal-page components.

Product pages may not introduce private colors, shadows, radii, focus styles or motion values when an existing semantic token or component applies. A new visual primitive is added to the design system first and then consumed by the feature.

Material Design is the common language; the pinned Chrome desktop behavior is its density and interaction interpretation. Generic mobile Material sizes do not override an observed desktop Chrome pattern.

## Chromium consultation rule

When a UI code detail is uncertain, inspect the matching Chromium tag rather than guessing or consulting current `main`. Relevant source normally begins in `chrome/browser/ui/views`, `ui/views`, `components/omnibox`, and the feature's own browser UI directory.

The consultation must answer a concrete question such as layout ownership, minimum/maximum sizing, focus traversal, accessible naming, event ordering, popup anchoring, overflow behavior or animation state. Translate that behavior into Navis XHTML/CSS/JavaScript and Core state; do not copy Chromium framework assumptions across the Navis boundary.

Any non-trivial translation records in its change or design evidence:

- the observed Chrome behavior;
- the Chromium tag, path and relevant symbol;
- the Navis component and Core state used to express it; and
- intentional deviations caused by capability, security, accessibility or OS ownership.

Chromium is a UI implementation reference only. It does not become Navis's web engine, public Core API, backend architecture or product-service dependency.

## Accessibility and input

Every component and complete surface must support:

- visible keyboard focus and logical traversal without pointer-only actions;
- accessible roles, names, descriptions, values and state changes;
- minimum contrast in light, dark, private and high-contrast environments;
- reduced-motion behavior without losing loading or completion information;
- CJK text, IME composition, bidirectional text, text scaling and truncation;
- pointer, wheel and touchpad behavior; and
- correct zoom/DPI geometry without clipping or unreachable actions.

Material appearance never overrides a secure or accessible interaction. Focus must not be removed merely because the Chrome reference makes it subtle for a pointer interaction.

## Internationalization

Every Navis-owned surface uses the shared Platform message contract in `navis-i18n.md`. `en-US` and `zh-CN` are complete release locales, including dynamic state and accessibility text; English literals are not a substitute for resource IDs. Components must size and wrap from content rather than from one English label, and Chinese text may not be clipped to preserve a reference screenshot made in English.

Locale-aware plural, date, time and number formatting comes from the shared formatter. Translations are always inserted as text or bounded attributes, never as trusted markup. Display-language changes are persistent and apply on restart so one running window cannot combine two catalogue generations.

OS-owned surfaces follow the OS language. Web content and the unmodified uBO dashboard keep their own localization ownership and are not rewritten by the Navis catalogue.

## Visual and interaction regression gate

A Navis-owned UI change is not complete with a single manual screenshot. Its smallest sufficient evidence includes the affected component states and a focused interaction replay. Release-critical surfaces also require reference images for:

- normal and maximized windows plus the supported minimum window size;
- light, dark and private themes;
- 100%, 125%, 150% and 200% effective scale where supported;
- empty, populated, loading, success, disabled and error states;
- keyboard focus and at least one long Chinese title/URL/value; and
- Linux and Windows native presentation.

Deterministic fixtures supply data for menus, permissions, downloads, password prompts, errors and crash recovery. Internet pages do not provide stable UI regression fixtures. Screenshot comparison detects geometry and styling changes; UI automation and accessibility-tree checks prove behavior and semantics.

## Definition of conformity

A Navis-owned surface conforms only when it:

1. contains no control for an absent capability;
2. uses shared Material design tokens and components;
3. matches the pinned Chrome reference in the states relevant to Navis;
4. preserves Core/Platform ownership and security boundaries;
5. passes keyboard, accessibility, theme, DPI and native-platform checks; and
6. records any intentional reference deviation.
