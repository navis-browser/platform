# Built-in extension registry

`extensions.json` is the product-owned collection of immutable built-in extensions. uBlock Origin is the first entry; neither the registry, application extension loader nor installer assumes it is the only entry.

Every new entry must have an explicit source entry in `moz.build`, pinned provenance and hashes in its metadata, and an official signed XPI. `required` means the artifact must be in every production package; it does not mean the user cannot disable the extension. `default_toolbar_pinned` is only the initial generic action placement and remains user-controllable. The package verifier rejects missing, altered and unregistered XPIs.

All entries share the same restricted runtime boundary: application scope only, no Web install or sideloading, no Add-ons Manager, no code self-update, and no `nativeMessaging` permission. Built-ins use the same action, popup, options and lifecycle surfaces as user extensions. Adding a built-in does not create a feature-specific Core/Binding contract or a second lifecycle owner.
