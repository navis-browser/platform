# Navis Platform

This is the independent Platform publication repository. It stores host/product projections, and depends on a Runtime revision selected by the sibling `navis` composition repository. Runtime never depends on Platform.

The desktop web surface is shared in:

- `platform/gecko-chrome/` — shared desktop shell (Linux/Windows/macOS baseline).
- Linux x86_64 and Windows x86_64 are native desktop targets. The macOS descriptor is not a tested browser product merely because it shares a shell.

Host overlays can either:

- own native host glue directly, or
- embed/reuse the shared desktop shell layout where appropriate.

Source responsibilities:

- `platform/android/` has an independent Kotlin/Compose projection and depends on the Core-private engine module in `runtime/android/`.
- `platform/linux/` / `platform/windows/` / `platform/macos/` are descriptor-first host-overlay directories that can grow into concrete glue once a host divergence is accepted.
- `platform/harmonyos/` is reserved for future ArkUI integration.

All desktop host overlays should be treated as **descriptors first**: they define where host-specific package/launcher/policy glue can live, while shared chrome UI/layout behavior stays in `platform/gecko-chrome/`.

Each active host overlay includes `host-overlay.json` to record:

- what it overrides,
- why the override is host-scoped,
- and how acceptance is tied back to the relevant platform contract.

New work should use this directory boundary and keep `runtime/` dependency-free from desktop UI internals.

The intended publication slug is `navis-browser/platform`. Keeping the shared desktop chrome, Android projection and host overlays in one repository makes cross-platform product parity reviewable in one diff; this does not prevent a later history-preserving subtree extraction if a platform gains an independent release cadence.

History note: `v0.1.0` contains only `gecko-chrome/`. Android and the explicit host-overlay descriptors enter the repository on the later development line.

For clone/compose/rollback instructions see the sibling Navis repository's `navis/docs/repository-publication.md`. Platform is not a standalone Android Studio project: its Gradle module consumes the matching prepared Runtime/Gecko graph. Navis-owned files use MPL-2.0; bundled upstream archives retain the licenses, notices and corresponding-source directions in THIRD_PARTY_NOTICES.md.

## Develop Platform

The [UI contract](docs/navis-ui-standard.md) and [localization contract](docs/navis-i18n.md) live with the UI implementation. `tests/` owns desktop UI component behavior; Android unit tests stay in the standard Gradle test source set. Cross-Runtime integration tests belong to Navis.

```sh
node tests/omnibox-edit-state.mjs
node tests/localization-core.mjs
```

Where present at the selected version, `scripts/sync-android-brand.mjs` and `scripts/sync-android-site-presentation.mjs` regenerate shared Platform resources with `--write` or detect drift with `--check`. The brand generator additionally uses the SVG/PNG tools documented by its source. Linux portable-package desktop registration is `scripts/register-linux-desktop-integration.py --help`; it is explicit, user-scoped and does not change the default browser.
