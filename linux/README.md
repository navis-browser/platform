# Linux platform shell

This folder is the host-specific anchor for Linux desktop integration.

- Start from `platform/gecko-chrome/` for shared desktop UI/layout runtime.
- Keep Linux packaging hooks, distro-specific manifests, and platform-specific service adapters in this folder when the host layer diverges from the shared desktop shell.

Current migration phase: descriptor-first host overlay with no concrete shell UI duplication. Shared desktop shell from `platform/gecko-chrome/` remains authoritative until a host-specific divergence is contractually accepted.

Host overlay contract for Linux:

- `host-overlay.json` declares package/launcher/policy override points.
- Keep host-specific implementation files in this directory only when they cannot be represented in shared shell contracts.
