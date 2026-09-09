# Windows platform shell

This folder is the host-specific anchor for Windows desktop integration.

- Start from `platform/gecko-chrome/` for shared desktop UI/layout runtime.
- Keep Windows shell manifests, installer glue, and host integration overrides here when they diverge from the shared desktop shell.

Current migration phase: descriptor-first host overlay with no concrete shell UI duplication. Shared desktop shell from `platform/gecko-chrome/` remains authoritative until a host-specific divergence is contractually accepted.

Host overlay contract for Windows:

- `host-overlay.json` records integration ownership for installer/update/launch and any host-only policy overrides.
- Host-specific files are added in this directory only when shared-shell contracts cannot express the required behavior.
