# uBlock Origin production input

Navis embeds the official, unmodified Firefox release of uBlock Origin
1.74.0. The signed XPI is stored byte-for-byte as downloaded from Mozilla
Add-ons. Its identity, size, digest, required permissions, upstream release,
source commit and licence are pinned in `metadata.json` and checked before
every package is produced.

The XPI is installed only from Navis's application-owned `extensions/`
directory. Navis does not unpack, patch, re-sign or independently update the
extension code. A new extension version therefore requires an explicit Navis
source change and application release. uBlock Origin's filter-list updater is
separate and remains governed by its own user-visible controls.

Corresponding source:

- repository: <https://github.com/gorhill/uBlock>
- release: <https://github.com/gorhill/uBlock/releases/tag/1.74.0>
- commit: `6dd2d95e50d134a477a4e183343c0b26e9147123`
- licence: GPL-3.0-or-later; the complete GPL text is also present as
  `LICENSE.txt` inside the XPI.
