# Navis Platform instructions

This repository owns Navis product projections: shared desktop chrome, the Android Kotlin/Compose application and target host overlays. It consumes the public Runtime contract and must not create a second lifecycle or product-policy authority by reaching around that boundary.

Read this repository's README and relevant local `docs/` contracts first. When checked out in the standard sibling workspace, also read the relevant public contracts under `../navis/docs/` before substantial work. If the sibling repository is absent, limit work to the ownership and parity contract in this README rather than inventing requirements.

- Linux, Windows and Android expose one capability set; layout, gestures and host integration may adapt, but settings, persistence, permissions and failure semantics remain aligned.
- Desktop Navis-owned UI follows the accepted Chrome/Material contract; Android uses official Kotlin/Compose Material 3 components where practical.
- Fix shared interaction families at their common root. Do not accumulate page-local token, menu, focus, motion or state patches.
- Prefer the smallest complete change and existing platform-native facilities. Do not add a second UI framework or speculative platform layer.
- Native automation must target only the tested Navis processes/windows, use isolated test data and clean up its own resources without affecting unrelated applications. Select coverage from the changed UI and platform boundaries.
