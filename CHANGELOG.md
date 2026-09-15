# Changelog

All notable changes to the Linklab Android SDK are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] - 2026-09-15

First versioned release. Cross-platform behaviour is aligned with the iOS and Flutter SDKs.

### Breaking changes

- `LinkData.rawLink` renamed to `fullLink`. `rawLink` remains as a deprecated alias for one release.
- `processDynamicLink(intent)` now returns `false` and delivers **nothing** for intents whose
  URL is not a Linklab link (non-`http(s)` scheme, or a host other than `linklab.cc`,
  `*.linklab.cc` or a configured custom domain). Previously such intents were delivered as
  `unrecognized`. Handle your own deep links in the `false` branch.
- Custom domains now match the host exactly (case-insensitive); subdomains of a custom domain are
  no longer implied.
- `minSdk` lowered from 27 to 21.
- Jetpack Compose runtime/UI and AppCompat are no longer dependencies of the SDK.
- `LinkData.userId` removed. The server value is never exposed.
- `LinkData.parameters` is non-null (`Map<String, String>`, possibly empty).
- Default `networkTimeout` reduced from 30 s to 10 s.
- `getDynamicLink(uri)` returns `Boolean` (false for non-Linklab URIs) instead of `Unit`.

### Added

- `LinkData.shortLink`, `resolutionStatus` (`resolved` / `unrecognized` / `failed`),
  `errorMessage`, `isDeferred`, `matchType` (`direct` / `installReferrer`).
- `LinkLabConfig.baseUrl`, `installReferrerEnabled`; Java-friendly `LinkLabConfig.Builder`.
- `LinkLab.isLinkLabLink(Uri)`, `LinkLab.setHttpClient(OkHttpClient)`, `LinkLab.VERSION`.
- Network retries with exponential backoff (500 ms, 1 s, 2 s) for IOExceptions and 5xx; 4xx are
  never retried. 404 yields `unrecognized`, other failures `failed` with `errorMessage`.
- Request headers `Accept`, `User-Agent: Linklab-Android-SDK/<version> (...)`,
  `X-Linklab-Sdk`, `X-Linklab-App`.
- Root-path Linklab URLs (`https://<host>/?...`) are delivered as `unrecognized` immediately,
  so query-only landing pages still reach the app.
- A listener registered after a link was delivered receives the last link once.
- Exactly-once delivery per in-flight URL; re-opening a link after completion processes it again
  (the permanent "already processed" cache is gone).
- Deferred deep link state machine persisted in SharedPreferences (`pending`/`done`, up to 3
  attempts within 24 h). Install referrer accepted both as plain `linklab_id=<id>&domain=<host>`
  and base64-encoded.
- Server `domainType: "default"` is mapped to `"linklab"`.
- Thread-safe ISO-8601 parsing with or without fractional seconds and zone offsets.
- JVM unit tests (Robolectric + MockWebServer) and a GitHub Actions CI workflow.

### Fixed

- Listener exceptions no longer prevent delivery to the remaining listeners.
- HTTP responses are always closed.
- Debug logging never includes query strings.
- Install Referrer connection is always closed and reconnects at most once on disconnect.

### Removed

- Hard-coded signing configuration, `AD_ID` permission and cleartext traffic from the sample app.
- Unused resources, Compose theme files and template tests.
