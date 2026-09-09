# Packaged application locales

Navis ships `en-US` in its normal Gecko resource graph and the exact official Mozilla `zh-CN` language pack matched to the pinned Gecko ESR. The reviewed XPI is stored in this source tree and copied byte-for-byte into desktop packages; startup never downloads a language pack. Source metadata pins its size, SHA-256, ESR compatibility range and Mozilla provenance, while package verification independently requires the installed XPI's SHA-256 to equal the source XPI's SHA-256.

The language pack is immutable application data: it is packaged offline, not shown in the extension manager, and cannot be installed, updated, disabled or removed through the user-extension surface. Moving Navis to another Gecko ESR therefore requires reviewing and replacing this package together with the ESR, not allowing an autonomous locale update.

The application-global package allowlist contains both reviewed WebExtension IDs and reviewed locale-pack IDs. The two types remain separate in their product registries and in AddonManager projections.
