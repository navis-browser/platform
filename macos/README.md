# macOS platform shell

This folder is reserved for future macOS host integration.

- The desktop chrome baseline is expected to be reused from `platform/gecko-chrome/` via a host-specific native glue layer.
- Platform-specific windowing, signing/integration and packaging files should be placed here when implemented.

Current migration phase: descriptor-first host overlay. Shared shell logic stays in `platform/gecko-chrome/`.

Host overlay contract for macOS:

- `host-overlay.json` will record signing/windowing/package boundary decisions once host-specific implementation begins.
