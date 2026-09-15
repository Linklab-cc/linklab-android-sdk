# Linklab Android SDK

Resolve [Linklab](https://linklab.cc) dynamic links in your Android app: direct App Links
(`https://linklab.cc/...` or your custom domain) and deferred deep links delivered through the
Google Play Install Referrer after a fresh install.

- Kotlin, single artifact `cc.linklab:android`
- minSdk 21, compileSdk 36
- Dependencies: OkHttp 4, Play Install Referrer 2.2, AndroidX core

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("cc.linklab:android:0.1.0")
}
```

The SDK declares `android.permission.INTERNET` in its manifest; nothing else is required.

## Initialisation

Initialise once (Application or launcher Activity) with a `LinkLabConfig`:

```kotlin
// Kotlin
val config = LinkLabConfig(
    customDomains = listOf("links.example.com"), // hosts you registered in Linklab
    debugLoggingEnabled = BuildConfig.DEBUG,
)
LinkLab.getInstance(context).init(config).addListener(listener)
```

```java
// Java
LinkLabConfig config = new LinkLabConfig.Builder()
        .customDomains(Arrays.asList("links.example.com"))
        .debugLoggingEnabled(BuildConfig.DEBUG)
        .build();
LinkLab.getInstance(context).init(config).addListener(listener);
```

| option | default | meaning |
|---|---|---|
| `customDomains` | `[]` | Extra hosts treated as Linklab links (exact match, case-insensitive). `linklab.cc` and `*.linklab.cc` are always recognised. |
| `debugLoggingEnabled` | `false` | Logcat output under tag `LinkLab`. |
| `networkTimeout` | `10.0` | Per-call connect/read/write timeout in seconds. |
| `networkRetryCount` | `3` | Retries for network errors and 5xx responses (backoff 500 ms, 1 s, 2 s). 4xx is never retried. |
| `baseUrl` | `https://linklab.cc` | API base URL. |
| `installReferrerEnabled` | `true` | Resolve deferred deep links from the Play Install Referrer on first launch. |

`init` may be called again; the latest config wins.

## Receiving links

Implement `LinkLab.LinkLabListener` and forward every incoming intent to
`processDynamicLink(intent)`:

```kotlin
class MainActivity : AppCompatActivity(), LinkLab.LinkLabListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkLab.getInstance(this).init(LinkLabConfig(customDomains = listOf("links.example.com")))
            .addListener(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (!LinkLab.getInstance(this).processDynamicLink(intent)) {
            // Not a Linklab link (or no data): handle your own deep links here.
            intent?.data?.let { handleOwnDeepLink(it) }
        }
    }

    override fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkLab.LinkData) {
        when (data.resolutionStatus) {
            "resolved" -> navigate(fullLink, data.parameters)
            "unrecognized", "failed" -> navigate(fullLink, data.parameters) // fail-open with the original URL
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LinkLab.getInstance(this).removeListener(this)
    }
}
```

### `processDynamicLink(intent)` semantics

- Returns **`false`** and delivers **nothing** when the intent has no data, the URL is not
  `http(s)`, or the host is neither `linklab.cc`, `*.linklab.cc`, nor one of your
  `customDomains`. Your app handles such intents itself.
- Returns **`true`** for Linklab URLs; the result arrives asynchronously on the main thread via
  `onDynamicLinkRetrieved`:
  - `resolutionStatus = "resolved"` when the server knows the link;
  - `"unrecognized"` when the server returns 404 **or** the URL has no path (e.g.
    `https://links.example.com/?campaign=x` - delivered immediately without a network call so
    query-only landing pages still reach the app);
  - `"failed"` with `errorMessage` when the request fails after all retries.

  For `"unrecognized"`/`"failed"`, `fullLink` is the original URL and `parameters` are its query
  parameters, so you can always fail open.
- The same URL is processed at most once while a request for it is in flight; re-opening it after
  completion processes it again.
- A listener added **after** a link was delivered receives the most recent link once. This makes
  it safe to register listeners from screens that appear after the launch intent was processed.
- `getDynamicLink(uri)` does the same for a bare `Uri`; `isLinkLabLink(intent|uri)` answers the
  host check without side effects.

### `LinkData`

| field | type | notes |
|---|---|---|
| `id` | `String?` | server link id; `null` for unrecognized/failed |
| `fullLink` | `String` | destination URL; for unrecognized/failed the original URL |
| `shortLink` | `String?` | URL as received by the app; `null` for install-referrer links |
| `createdAt` / `updatedAt` | `Long?` | epoch millis |
| `packageName`, `bundleId`, `appStoreId` | `String?` | as configured in Linklab |
| `domain` | `String?` | host |
| `domainType` | `String` | `"linklab"`, `"custom"` or `"unrecognized"` |
| `parameters` | `Map<String,String>` | never null: query params of `fullLink` (URL-decoded), overridden by server-side parameters |
| `resolutionStatus` | `String` | `"resolved"`, `"unrecognized"`, `"failed"` |
| `errorMessage` | `String?` | set when `resolutionStatus == "failed"` |
| `isDeferred` | `Boolean` | `true` for install-referrer links |
| `matchType` | `String` | `"direct"` or `"installReferrer"` |

`rawLink` is kept as a deprecated alias of `fullLink` for one release.

## App Links setup

Declare the Linklab hosts on the Activity that should receive links, with `autoVerify` so Android
opens them directly in your app:

```xml
<activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" />
        <data android:host="linklab.cc" />
        <data android:host="links.example.com" />
    </intent-filter>
</activity>
```

Each host must serve `https://<host>/.well-known/assetlinks.json` containing your package name
and the SHA-256 fingerprint(s) of your signing certificate(s) (upload key **and** Play App
Signing key):

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.example.app",
    "sha256_cert_fingerprints": ["AA:BB:..."]
  }
}]
```

For `linklab.cc` and your custom domains, enter the package name and fingerprints in the Linklab
dashboard; Linklab serves the file for you. Verify with
`adb shell pm get-app-links com.example.app`.

## Deferred deep links (install referrer)

When a user without the app taps a Linklab link, Linklab sends them to Google Play with a
referrer of the form

```
linklab_id=<id>&domain=<host>
```

(URL-encoded values; the base64-encoded form of the same string is also accepted). On the first
launch after install the SDK reads the Play Install Referrer, resolves the link and delivers it
through the same listener with `isDeferred = true`, `matchType = "installReferrer"` and
`shortLink = null`. Organic installs (no `linklab_id`) deliver nothing.

The check runs at most 3 times and only within 24 hours of the first launch; transient failures
(Play Services unavailable, network errors) are retried on the next `init`, definitive outcomes
(link delivered, link not found, no Linklab referrer) end it. Set
`installReferrerEnabled = false` to disable it entirely.

## Logging and privacy

Nothing is logged unless `debugLoggingEnabled = true`. Even then the SDK never logs query strings
or fragments - only `scheme://host/path` - so parameters such as tokens or user identifiers never
reach Logcat. Requests to the Linklab API carry `User-Agent`, `X-Linklab-Sdk` and
`X-Linklab-App` (your package name) headers and no other identifiers. The SDK stores only the
deferred-check state (`linklab_prefs`) in SharedPreferences.

## Sample app

`sample/` contains a minimal Activity showing initialisation, intent handling and the App Links
intent-filter. Run it with `./gradlew :sample:installDebug` and open a link with
`adb shell am start -a android.intent.action.VIEW -d "https://linklab.cc/<id>"`.

## Publishing

See [PUBLISHING.md](PUBLISHING.md).

## License

Apache License, Version 2.0 - see [LICENSE](LICENSE).
