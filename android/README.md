# Navis Android Platform

`android/` is the source of record for the Navis Android application. The pinned Gecko tree mounts it as `navis-android/`; generated Gradle outputs and Gecko object directories do not belong here.

Navis Android is a direct Gecko-derived embedder. It does not include the `:geckoview` project, consume a GeckoView AAR, or use GeckoView's public Runtime/Session/View owners:

```text
Kotlin / Compose Material Platform UI
                  |
       Navis Browser/Target contracts
                  |
 AndroidBrowserRuntime / AndroidSession / AndroidBrowserView
                  |
  EngineRuntimePort and bounded feature ports ---- Rust Core JNI
                  |                                  |
    Navis-private Java/JNI engine primitives     shared Navis Core
                  |
       Gecko DOM / JS / network / WebRender
```

The private `:navis-runtime-android` module is owned by `runtime/android/`, not this Platform tree. It packages the configured native stage, `omni.ja`, generated JNI and an allowlisted low-level Java source closure. Historical `org.mozilla.*` package names inside that module are implementation details, not product APIs. Final DEX gates reject `GeckoRuntime`, `GeckoSession`, `GeckoView` and the other public lifecycle owners.

## Build and validation

Use the matching sibling Runtime and Navis revisions. Build profiles and package checks are owned by that repository composition; see `../../navis/docs/desktop-embedder-source-package.md` and the applicable Android contract under `../../navis/docs/`. Native objects, SDKs, device observations and candidate archives are separate build inputs or private evidence, not files required from this repository.
