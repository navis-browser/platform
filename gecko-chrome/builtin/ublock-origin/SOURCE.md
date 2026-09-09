# uBlock Origin production input

Navis embeds the official, unmodified Firefox release of uBlock Origin 1.74.0. The signed XPI is stored byte-for-byte as downloaded from Mozilla Add-ons. Its identity, size, digest, required permissions, upstream release, source commit and licence are pinned in `metadata.json` and checked before every package is produced.

Desktop installs the unchanged signed XPI from Navis's application-owned `extensions/` directory. Android verifies that same source XPI and extracts its entries into the APK's `assets/web_extensions/ublock-origin/` directory; package verification compares every extracted entry against the original archive. Extraction does not preserve an XPI container as the installed Android artifact and is not a claim of Android XPI-signature verification. Neither platform patches the extension code or re-signs the upstream XPI.

A new bundled extension version requires an explicit Navis source change and application release. uBlock Origin's filter-list updater is separate and remains governed by its own user-visible controls. Preserve `LICENSE.txt` and all nested component/filter/font notices on both platforms. A release download must give clear access to the matching corresponding source, including build scripts and required subdependencies; a link to AMO or to a moving repository branch is not a substitute. The distributor remains responsible for source availability even when another server hosts it.

Corresponding source:

- repository: <https://github.com/gorhill/uBlock>
- release: <https://github.com/gorhill/uBlock/releases/tag/1.74.0>
- commit: `6dd2d95e50d134a477a4e183343c0b26e9147123`
- licence: GPL-3.0-or-later; the complete GPL text is also present as `LICENSE.txt` inside the XPI.
