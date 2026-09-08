# Third-party source and notices

Navis-owned source is MPL-2.0 unless otherwise marked; see LICENSE. Upstream
files retain their own notices. Repository publication includes the following
unmodified third-party archives; their licenses are not replaced by MPL.

## uBlock Origin 1.74.0

GPL-3.0-or-later. The signed XPI and its exact source commit/download/hash are
in `gecko-chrome/builtin/ublock-origin/{SOURCE.md,metadata.json}`. The XPI includes
`LICENSE.txt` and notices for its embedded libraries/filter data. Do not modify
or re-sign it while claiming the original signature/hash.

Corresponding source, build scripts and subdependency references:
https://github.com/gorhill/uBlock/tree/6dd2d95e50d134a477a4e183343c0b26e9147123

Source archive (follow the repository's build/subdependency instructions):
https://github.com/gorhill/uBlock/archive/6dd2d95e50d134a477a4e183343c0b26e9147123.tar.gz

Anyone publishing this XPI must retain equivalent source access alongside the
binary. Recheck the upstream links or mirror complete corresponding source if
they become unavailable; a signed AMO download is not itself a source offer.

## Mozilla Simplified Chinese language pack

MPL-2.0. The unmodified Firefox ESR language archive is pinned by
`gecko-chrome/locales/zh-CN/metadata.json`; this repository's LICENSE contains
the MPL text. It is translation source, with notices in the FTL/property files.
Upstream source: https://github.com/mozilla-l10n/firefox-l10n
Release and checksums: https://archive.mozilla.org/pub/firefox/releases/153.1.0esr/

## Android resolved dependencies

Kotlin, AndroidX/Compose and Material dependencies remain Gradle-resolved, not
vendored binaries in this repository. See `android/build.gradle`. The selected
colorpicker-compose Apache-2.0 notice is owned/packaged by the sibling Runtime.
Gecko-derived desktop styles/resources retain their upstream file headers.
This source notice is not a complete binary-distribution license inventory.
