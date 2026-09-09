# HarmonyOS platform shell

This folder is reserved for HarmonyOS planning and implementation.

- Use this as the host-specific path for ArkUI/Compose-like integration where the platform strategy is validated.
- Keep all HarmonyOS-only glue here and avoid moving shared desktop logic.
- Planning does not imply inheritance from either the desktop Gecko chrome or Android/Compose projection; that choice requires a validated HarmonyOS runtime and UI integration decision.

Current migration phase: planning-only.

Host overlay contract for HarmonyOS:

- `host-overlay.json` reserves this path for future production-ready host glue and records acceptance criteria for when that implementation phase begins.
