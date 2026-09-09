# Navis brand assets

`navis-mark.json` is the production source of truth, transcribed from the approved shapes in `docs/navis-logo-shapes.html`. The HTML remains a design reference, not an input scraped at build time. Edit the JSON and regenerate; do not hand-edit generated SVG, PNG, ICO, RC, or `brand-geometry.mjs`.

```sh
python3 gecko-chrome/branding/generate_brand_assets.py
python3 gecko-chrome/branding/generate_brand_assets.py --check
```

Generation uses Python's standard library and `rsvg-convert`. The manifest records the full renderer/library version plus the canonical source and output SHA-256 hashes. Use that renderer stack for byte-identical regeneration; there are no timestamps, fonts, network inputs, or random seeds. `--check` regenerates in memory and never modifies the workspace. A normal Gecko build only packages the committed results and does **not** require an SVG renderer.

Schema version 1 uses SVG viewBox units. `contour` is the rounded rectangle; `transform` applies to `channel` and both `seams.paths`, before contour clipping. The default radius is 40 (the design page's CSS default); `contourVariants.circle` is 88. All gradients, frost/rim layers and relief shadows are explicit. `motion` carries the original Counterflow and Comet timing in milliseconds, with a static reduced-motion policy. Platform motion/lifecycle belongs to the consumer, not to the static asset. The generated JS exports deep-frozen `NAVIS_MARK` (also default), `geometry`, `colors`, and `motion`.

Outputs and packaging:

- `generated/navis-mark.svg`, `navis-mark-circle.svg`: self-contained color marks with the complete gradient/frost/rim/relief treatment.
- `generated/navis-mark-mono.svg`: opaque white contour with the two seams cut out transparently, for platform tinting; no independent replacement glyph.
- `generated/navis-{size}.png`: transparent RGBA at 16–512px, using the rounded mark. The source JSON lists the exact sizes.
- `generated/navis.ico`: nine exact PNG entries from 16–256px for Windows Vista+ (Navis targets modern Windows). Its generated RC uses the existing Gecko resource IDs, including the private-window fallback, and contains the ICO hash so icon changes invalidate the RC dependency.
- Windows `gecko-chrome/app/moz.build` sets only the product `RCINCLUDE` and icon define. Future builds must relink the launcher resource, not the engine.
- Windows VisualElements 70/150px tiles and the adjacent executable manifest are installed regardless of whether notification activation is enabled.
- Linux `chrome/icons/default/default{16,32,48,64,128,256}.png` follows Gecko's existing GTK lookup. The generator does not mutate the host. A portable runtime can be registered explicitly with `scripts/register-linux-desktop-integration.py`; it derives the Wayland app id from that runtime's `application.ini` and maps these exact pixels into the user's XDG hicolor theme. This is required on GTK3/Wayland desktops that do not receive window icon pixels through the compositor protocol.
- Desktop pages render from generated `brand-geometry.mjs`. Gecko's retained `CommonDialog.DEFAULT_APP_ICON_CSS` separately requests standard `chrome://branding/content/icon16.png`, `icon32.png`, and `icon64.png`. The lightweight branding content JAR maps those names, plus `icon48.png` (controlcenter) and `icon128.png` (wizard), to the canonical `default*.png`. `about-logo.svg` maps to the canonical SVG for Gecko toolkit references. These six entries and existing localization are the entire branding JAR; no other sizes/variants are packaged, and no additional toolkit surface is enabled by registering the assets.
- The direct Android runtime maps its historical `about.png` and `favicon{32,64}.png` names plus Toolkit's standard icon aliases to this same generated family. It does not select or copy Fennec artwork.
- Signed Mozilla language packs remain immutable translation inputs. The Navis Core language-pack boundary filters their legacy branding alias and uses an exact-index localization fallback for the two brand Fluent resources, so a locale switch cannot replace Navis product terms.

Static generator/package contracts do not prove a rebuilt executable's icon resources or desktop-shell cache behavior. Those require native package/runtime validation on the relevant operating system.
