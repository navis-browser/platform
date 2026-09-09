# Navis brand system

## Authority

`docs/navis-logo-shapes.html` is the visual design reference. The checked-in machine-readable source is `gecko-chrome/branding/navis-mark.json`; generated SVG, PNG, ICO, desktop JavaScript geometry and Android resources must not become independent design sources.

The mark has a fixed blue-to-violet palette. A user's interface accent and light/dark preference may change the surface around it, but may not recolor the full-colour mark. Platform-owned monochrome contexts, such as Android themed icons and notification small icons, use the generated monochrome silhouette.

## Shape variants

- The primary mark uses the canonical `0 0 220 220` view box and the 176-unit contour with radius 40.
- Large product-owned surfaces preserve the contour, channel, two seams, frost, rim and relief treatment.
- Compact 16--24 px output preserves the same silhouette and path geometry but may omit sub-pixel shadows and frost. Optical simplification is not a licence to redraw the mark.
- Android adaptive-icon masks own the final outer crop. The foreground keeps the recognisable geometry inside the platform safe zone rather than baking a second circular or squircle brand into the asset.

Normal website favicons, extension icons, profile avatars, TLS state, warnings, errors and download-state glyphs retain their own identity. A Navis mark never claims that remote Web content is trusted.

## Required surfaces

The generated static family covers Linux window/task-switcher icons, Windows EXE/taskbar and VisualElements icons, Android legacy/adaptive/round/themed launcher forms, internal-page favicons and trusted internal-page identity.

Language packs translate Gecko UI but do not own the embedding product's identity. Navis keeps the original signed locale archive byte-for-byte, ignores its legacy `branding` alias, and supplies only `branding/brand.ftl` and `toolkit/branding/brandings.ftl` from the application source in the same localization metasource. This keeps ordinary zh-CN translations intact while preventing a Firefox or Fennec name from replacing Navis in desktop, Android, DevTools or content-process surfaces.

Product-owned new-tab and About surfaces use the full mark. Settings and other internal pages use the full or compact static form according to available space. Android app-owned notifications use a dedicated monochrome small icon; download progress, completion and failure continue to use stateful actions and wording.

## Motion

Counterflow is a one-shot entrance: the base becomes visible over 720 ms and the two seams draw from opposite ends for 920 ms after a 420 ms delay. New-tab and About may use it when their mark first becomes visible. It must not hold up page interaction, navigation or startup.

Full loading consists of that 1.34 s entrance followed by Comet while, and only while, a real Core-starting state remains pending. Core readiness cancels the loading presentation immediately. Ordinary website loading retains the normal favicon/progress semantics and never substitutes a looping brand mark.

Motion settles to the static final frame when system reduced-motion/animation settings request it, when a surface becomes hidden, or when its lifecycle ends. Idle home, OS launcher, taskbar, favicon and notification surfaces do not loop. Animation implementations must not force synchronous layout or add an artificial minimum display time.

## Platform implementation boundary

Desktop uses the existing privileged XHTML/CSS/JavaScript design layer. Android uses Compose, VectorDrawable and system adaptive/splash facilities. Neither platform introduces a WebView, Lottie, a second renderer or a Runtime API solely to display the brand. Geometry and timing are shared; lifecycle and rendering adapters remain Platform-owned.

Generated assets are committed so normal builds do not require an SVG renderer. The generator's check mode must reproduce them byte-for-byte. Windows EXE icon changes require only the product launcher resource link, not a `xul` rebuild; desktop page assets and Linux icons remain resource-package changes, and Android changes retain the existing native libraries.

## Regression gate

Acceptance requires all of the following:

1. the canonical generator is clean in check mode;
2. no legacy plain-`N`, compass or independently drawn VisualElements brand remains on a required surface;
3. full, compact and monochrome output is present at the required platform sizes, including high-DPI and Android adaptive masks;
4. internal-page CSP, website favicon semantics, notification state semantics, reduced motion, hidden/background lifecycle and signed language-pack provenance remain intact;
5. Linux, Windows and Android packages are proven to contain the current generated assets rather than stale build output; and
6. final appearance is confirmed by the user on all three running products.
