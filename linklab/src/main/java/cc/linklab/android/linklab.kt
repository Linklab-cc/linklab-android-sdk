package cc.linklab.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Configuration for the LinkLab SDK.
 *
 * Kotlin callers can use the constructor with named arguments; Java callers should use
 * [LinkLabConfig.Builder].
 *
 * @property customDomains Additional hosts (exact match, case-insensitive) that should be treated
 *   as Linklab links, e.g. `listOf("links.example.com")`. `linklab.cc` and `*.linklab.cc` are
 *   always recognised.
 * @property debugLoggingEnabled Enables Logcat output (tag `LinkLab`). Query strings are never logged.
 * @property networkTimeout Per-call connect/read/write timeout in seconds.
 * @property networkRetryCount Number of retries for network errors and 5xx responses
 *   (exponential backoff: 500 ms, 1 s, 2 s, ...). 4xx responses are never retried.
 * @property baseUrl Linklab API base URL.
 * @property installReferrerEnabled Whether to resolve deferred deep links from the Google Play
 *   Install Referrer on first launch.
 */
class LinkLabConfig @JvmOverloads constructor(
    customDomains: List<String> = emptyList(),
    val debugLoggingEnabled: Boolean = false,
    val networkTimeout: Double = 10.0,
    val networkRetryCount: Int = 3,
    val baseUrl: String = DEFAULT_BASE_URL,
    val installReferrerEnabled: Boolean = true,
) {
    /** Custom domains, normalised to lower case. */
    val customDomains: List<String> = customDomains.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }

    /** Java-friendly builder. */
    class Builder {
        private var customDomains: List<String> = emptyList()
        private var debugLoggingEnabled: Boolean = false
        private var networkTimeout: Double = 10.0
        private var networkRetryCount: Int = 3
        private var baseUrl: String = DEFAULT_BASE_URL
        private var installReferrerEnabled: Boolean = true

        fun customDomains(domains: List<String>): Builder = apply { customDomains = domains }
        fun debugLoggingEnabled(enabled: Boolean): Builder = apply { debugLoggingEnabled = enabled }
        fun networkTimeout(seconds: Double): Builder = apply { networkTimeout = seconds }
        fun networkRetryCount(count: Int): Builder = apply { networkRetryCount = count }
        fun baseUrl(url: String): Builder = apply { baseUrl = url }
        fun installReferrerEnabled(enabled: Boolean): Builder = apply { installReferrerEnabled = enabled }

        fun build(): LinkLabConfig = LinkLabConfig(
            customDomains = customDomains,
            debugLoggingEnabled = debugLoggingEnabled,
            networkTimeout = networkTimeout,
            networkRetryCount = networkRetryCount,
            baseUrl = baseUrl,
            installReferrerEnabled = installReferrerEnabled,
        )
    }

    override fun toString(): String =
        "LinkLabConfig(customDomains=$customDomains, debugLoggingEnabled=$debugLoggingEnabled, " +
            "networkTimeout=$networkTimeout, networkRetryCount=$networkRetryCount, baseUrl=$baseUrl, " +
            "installReferrerEnabled=$installReferrerEnabled)"

    companion object {
        const val DEFAULT_BASE_URL = "https://linklab.cc"
    }
}

/**
 * LinkLab resolves Linklab dynamic links (direct App Links and deferred install-referrer links)
 * and delivers the result to registered [LinkLabListener]s on the main thread.
 *
 * Typical usage:
 * ```
 * LinkLab.getInstance(context).init(LinkLabConfig(customDomains = listOf("links.example.com")))
 *     .addListener(listener)
 * // in onCreate / onNewIntent:
 * if (!LinkLab.getInstance(context).processDynamicLink(intent)) { /* not a Linklab link */ }
 * ```
 */
class LinkLab private constructor(private val applicationContext: Context) {

    /** Callback interface for link results. All methods are invoked on the main thread. */
    interface LinkLabListener {
        /**
         * Called once per received link with the resolution result.
         *
         * @param fullLink the resolved destination (or the original URL when
         *   [LinkData.resolutionStatus] is not `"resolved"`). Equals `Uri.parse(data.fullLink)`.
         * @param data details about the link.
         */
        fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkData)

        /**
         * Called only for unexpected SDK-internal errors. Link resolution problems are reported
         * through [onDynamicLinkRetrieved] with `resolutionStatus = "failed"` instead.
         */
        fun onError(exception: Exception) {}
    }

    /**
     * Result of resolving a link. Field names match the iOS and Flutter SDKs.
     */
    data class LinkData(
        /** Server link id; null for unrecognized/failed links. */
        val id: String?,
        /** Destination URL; for unrecognized/failed links this is the original URL as received. */
        val fullLink: String,
        /** The URL as received by the app; null for install-referrer (deferred) links. */
        val shortLink: String?,
        /** Creation time, epoch millis. */
        val createdAt: Long?,
        /** Last update time, epoch millis. */
        val updatedAt: Long?,
        val packageName: String?,
        val bundleId: String?,
        val appStoreId: String?,
        /** Host of the link. */
        val domain: String?,
        /** `"linklab"`, `"custom"` or `"unrecognized"`. */
        val domainType: String,
        /** Query parameters of [fullLink] (URL-decoded) overridden by server-side parameters. Never null. */
        val parameters: Map<String, String>,
        /** `"resolved"`, `"unrecognized"` or `"failed"`. */
        val resolutionStatus: String,
        /** Set when [resolutionStatus] is `"failed"`. */
        val errorMessage: String?,
        /** True when obtained through the install referrer rather than an incoming intent. */
        val isDeferred: Boolean,
        /** `"direct"`, `"installReferrer"` or `"none"`. */
        val matchType: String,
    ) {
        @Deprecated("Use fullLink", ReplaceWith("fullLink"))
        val rawLink: String
            get() = fullLink

        companion object {
            const val STATUS_RESOLVED = "resolved"
            const val STATUS_UNRECOGNIZED = "unrecognized"
            const val STATUS_FAILED = "failed"

            const val DOMAIN_TYPE_LINKLAB = "linklab"
            const val DOMAIN_TYPE_CUSTOM = "custom"
            const val DOMAIN_TYPE_UNRECOGNIZED = "unrecognized"

            const val MATCH_DIRECT = "direct"
            const val MATCH_INSTALL_REFERRER = "installReferrer"
            const val MATCH_NONE = "none"

            /** A Linklab-domain URL that the server does not know (404) or that carries no link id. */
            @JvmStatic
            fun unrecognized(uri: Uri): LinkData = passthrough(uri, STATUS_UNRECOGNIZED, null)

            /** A Linklab-domain URL that could not be resolved because of a network/server/decoding error. */
            @JvmStatic
            fun failed(uri: Uri, message: String?): LinkData = passthrough(uri, STATUS_FAILED, message ?: "Unknown error")

            private fun passthrough(uri: Uri, status: String, message: String?) = LinkData(
                id = null,
                fullLink = uri.toString(),
                shortLink = uri.toString(),
                createdAt = null,
                updatedAt = null,
                packageName = null,
                bundleId = null,
                appStoreId = null,
                domain = uri.host,
                domainType = DOMAIN_TYPE_UNRECOGNIZED,
                parameters = queryParameters(uri),
                resolutionStatus = status,
                errorMessage = message,
                isDeferred = false,
                matchType = MATCH_DIRECT,
            )

            /**
             * Builds a resolved [LinkData] from the server JSON.
             *
             * @throws org.json.JSONException when `fullLink` is missing.
             */
            @JvmStatic
            @JvmOverloads
            fun fromJson(
                json: JSONObject,
                shortLink: String?,
                isDeferred: Boolean = false,
                matchType: String = MATCH_DIRECT,
            ): LinkData {
                val fullLink = json.getString("fullLink")
                val fullUri = Uri.parse(fullLink)
                val domain = json.optNullableString("domain") ?: shortLink?.let { Uri.parse(it).host }

                val domainType = when (json.optNullableString("domainType")?.lowercase(Locale.ROOT)) {
                    "custom" -> DOMAIN_TYPE_CUSTOM
                    "linklab", "default" -> DOMAIN_TYPE_LINKLAB
                    else -> if (isLinklabHost(domain)) DOMAIN_TYPE_LINKLAB else DOMAIN_TYPE_CUSTOM
                }

                val parameters = LinkedHashMap(queryParameters(fullUri))
                json.optJSONObject("parameters")?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (!obj.isNull(key)) parameters[key] = obj.opt(key).toString()
                    }
                }

                return LinkData(
                    id = json.optNullableString("id"),
                    fullLink = fullLink,
                    shortLink = shortLink,
                    createdAt = parseIso8601(json.optNullableString("createdAt")),
                    updatedAt = parseIso8601(json.optNullableString("updatedAt")),
                    packageName = json.optNullableString("packageName"),
                    bundleId = json.optNullableString("bundleId"),
                    appStoreId = json.optNullableString("appStoreId"),
                    domain = domain,
                    domainType = domainType,
                    parameters = parameters,
                    resolutionStatus = STATUS_RESOLVED,
                    errorMessage = null,
                    isDeferred = isDeferred,
                    matchType = matchType,
                )
            }

            /** URL-decoded query parameters of [uri]; empty for opaque or query-less URIs. */
            @JvmStatic
            fun queryParameters(uri: Uri): Map<String, String> {
                if (uri.isOpaque || uri.encodedQuery.isNullOrEmpty()) return emptyMap()
                val result = LinkedHashMap<String, String>()
                try {
                    for (name in uri.queryParameterNames) {
                        result[name] = uri.getQueryParameter(name) ?: ""
                    }
                } catch (_: UnsupportedOperationException) {
                    // opaque URI
                }
                return result
            }

            private fun JSONObject.optNullableString(key: String): String? =
                if (isNull(key)) null else opt(key)?.toString()?.takeIf { it.isNotEmpty() }

            private fun isLinklabHost(host: String?): Boolean {
                val h = host?.lowercase(Locale.ROOT) ?: return false
                return h == LINKLAB_HOST || h.endsWith(".$LINKLAB_HOST")
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<LinkLabListener>()

    @Volatile
    private var config: LinkLabConfig = LinkLabConfig()

    @Volatile
    private var injectedHttpClient: OkHttpClient? = null

    @Volatile
    private var builtHttpClient: OkHttpClient? = null

    private val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Incoming URLs currently being resolved (rule 7: exactly-once per in-flight URL). */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val deliveryLock = Any()
    private var lastDelivered: LinkData? = null
    private val receivedLastDelivered: MutableSet<LinkLabListener> = HashSet()

    private var referrerClient: InstallReferrerClient? = null
    private var referrerReconnectAttempted = false

    // ------------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------------

    /** Initialises the SDK. Safe to call more than once; the last config wins. */
    fun init(config: LinkLabConfig = LinkLabConfig()): LinkLab {
        this.config = config
        builtHttpClient = null
        log("Initialising with $config")
        if (config.installReferrerEnabled) {
            runDeferredCheck()
        } else {
            log("Install referrer check disabled by config")
        }
        return this
    }

    /**
     * Replaces the HTTP client used for API calls (e.g. to add interceptors or in tests).
     * Timeouts configured on the supplied client are used as-is.
     */
    fun setHttpClient(client: OkHttpClient?): LinkLab {
        injectedHttpClient = client
        return this
    }

    /**
     * Registers a listener. If a link was already delivered in this process, the listener
     * receives that last link once, immediately (on the main thread).
     */
    fun addListener(listener: LinkLabListener): LinkLab {
        listeners.addIfAbsent(listener)
        val replay = synchronized(deliveryLock) {
            val last = lastDelivered
            if (last != null && receivedLastDelivered.add(listener)) last else null
        }
        if (replay != null) {
            log("Replaying last delivered link to a late listener")
            mainHandler.post { safeDeliver(listener, replay) }
        }
        return this
    }

    fun removeListener(listener: LinkLabListener): LinkLab {
        listeners.remove(listener)
        synchronized(deliveryLock) { receivedLastDelivered.remove(listener) }
        return this
    }

    /** True if [intent] carries an http(s) URL whose host is `linklab.cc`, `*.linklab.cc` or a configured custom domain. */
    fun isLinkLabLink(intent: Intent?): Boolean = isLinkLabLink(intent?.data)

    /** True if [uri] is an http(s) URL whose host is `linklab.cc`, `*.linklab.cc` or a configured custom domain. */
    fun isLinkLabLink(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return false
        return host == LINKLAB_HOST || host.endsWith(".$LINKLAB_HOST") || config.customDomains.contains(host)
    }

    /**
     * Processes the URL carried by [intent].
     *
     * @return `true` if the URL is a Linklab link and a result will be (or was) delivered to the
     *   listeners; `false` if the intent has no data or the URL is not a Linklab link, in which
     *   case nothing is delivered and the app should handle the intent itself.
     */
    fun processDynamicLink(intent: Intent?): Boolean = getDynamicLink(intent?.data)

    /**
     * Resolves [shortLinkUri] and delivers the result to the listeners.
     *
     * @return `false` (and nothing is delivered) when the URI is null or not a Linklab link.
     */
    fun getDynamicLink(shortLinkUri: Uri?): Boolean {
        if (shortLinkUri == null) return false
        if (!isLinkLabLink(shortLinkUri)) {
            log("Ignoring non-Linklab URL ${redacted(shortLinkUri)}")
            return false
        }
        val linkId = shortLinkUri.lastPathSegment?.takeIf { it.isNotEmpty() }
        val host = shortLinkUri.host!!
        if (linkId == null) {
            log("No link id in ${redacted(shortLinkUri)}; delivering as unrecognized")
            deliver(LinkData.unrecognized(shortLinkUri))
            return true
        }

        val key = shortLinkUri.toString()
        if (!inFlight.add(key)) {
            log("Already resolving ${redacted(shortLinkUri)}; ignoring duplicate")
            return true
        }
        log("Resolving ${redacted(shortLinkUri)}")
        fetchLink(linkId, host, shortLink = key, isDeferred = false, matchType = LinkData.MATCH_DIRECT) { outcome ->
            inFlight.remove(key)
            when (outcome) {
                is FetchOutcome.Resolved -> deliver(outcome.data)
                FetchOutcome.NotFound -> deliver(LinkData.unrecognized(shortLinkUri))
                is FetchOutcome.Failed -> deliver(LinkData.failed(shortLinkUri, outcome.message))
            }
        }
        return true
    }

    // ------------------------------------------------------------------------------------------
    // Networking
    // ------------------------------------------------------------------------------------------

    private sealed class FetchOutcome {
        class Resolved(val data: LinkData) : FetchOutcome()
        object NotFound : FetchOutcome()
        class Failed(val message: String, val transient: Boolean) : FetchOutcome()
    }

    private fun httpClient(): OkHttpClient {
        injectedHttpClient?.let { return it }
        return builtHttpClient ?: synchronized(this) {
            builtHttpClient ?: buildHttpClient(config).also { builtHttpClient = it }
        }
    }

    private fun buildHttpClient(config: LinkLabConfig): OkHttpClient {
        val timeoutMs = (config.networkTimeout * 1000).toLong().coerceAtLeast(1)
        val callTimeoutMs = timeoutMs * (config.networkRetryCount.coerceAtLeast(0) + 1) + 1000
        return OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun linkUrl(linkId: String, domain: String): HttpUrl? {
        val base = config.baseUrl.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegment("links")
            .addPathSegment(linkId)
            .addQueryParameter("domain", domain)
            .build()
    }

    private fun buildRequest(url: HttpUrl): Request {
        val appId = applicationContext.packageName ?: "unknown"
        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Linklab-Android-SDK/$VERSION (Android ${Build.VERSION.RELEASE}; $appId)")
            .header("X-Linklab-Sdk", "android/$VERSION")
            .header("X-Linklab-App", appId)
            .build()
    }

    /**
     * Fetches `/links/{id}?domain=` with retries (rule 4) and reports a single [FetchOutcome]
     * to [onResult] (on an arbitrary thread).
     */
    private fun fetchLink(
        linkId: String,
        domain: String,
        shortLink: String?,
        isDeferred: Boolean,
        matchType: String,
        onResult: (FetchOutcome) -> Unit,
    ) {
        val url = linkUrl(linkId, domain)
        if (url == null) {
            onResult(FetchOutcome.Failed("Invalid baseUrl: ${config.baseUrl}", transient = false))
            return
        }
        val maxRetries = config.networkRetryCount.coerceAtLeast(0)
        val request = buildRequest(url)

        fun attempt(index: Int) {
            httpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    retryOrFail(index, "Network error: ${e.javaClass.simpleName}: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        when {
                            r.code == 404 -> {
                                log("Link $linkId not found (404)")
                                onResult(FetchOutcome.NotFound)
                            }
                            r.code in 500..599 -> retryOrFail(index, "Server error: HTTP ${r.code}")
                            !r.isSuccessful -> {
                                logError("Link $linkId request rejected: HTTP ${r.code}")
                                onResult(FetchOutcome.Failed("HTTP ${r.code}", transient = false))
                            }
                            else -> {
                                val body = try {
                                    r.body?.string()
                                } catch (e: IOException) {
                                    retryOrFail(index, "Network error while reading body: ${e.message}")
                                    return
                                }
                                if (body.isNullOrBlank()) {
                                    onResult(FetchOutcome.Failed("Empty response body", transient = false))
                                    return
                                }
                                try {
                                    val data = LinkData.fromJson(JSONObject(body), shortLink, isDeferred, matchType)
                                    log("Link $linkId resolved")
                                    onResult(FetchOutcome.Resolved(data))
                                } catch (e: Exception) {
                                    logError("Failed to decode link $linkId", e)
                                    onResult(FetchOutcome.Failed("Decoding error: ${e.message}", transient = false))
                                }
                            }
                        }
                    }
                }

                private fun retryOrFail(index: Int, message: String) {
                    if (index < maxRetries) {
                        val delay = RETRY_BASE_DELAY_MS shl index
                        log("Attempt ${index + 1} for link $linkId failed ($message); retrying in ${delay}ms")
                        mainHandler.postDelayed({ attempt(index + 1) }, delay)
                    } else {
                        logError("Link $linkId failed after ${index + 1} attempt(s): $message")
                        onResult(FetchOutcome.Failed(message, transient = true))
                    }
                }
            })
        }
        attempt(0)
    }

    // ------------------------------------------------------------------------------------------
    // Delivery
    // ------------------------------------------------------------------------------------------

    private fun deliver(data: LinkData) {
        mainHandler.post {
            val targets = listeners.toList()
            synchronized(deliveryLock) {
                lastDelivered = data
                receivedLastDelivered.clear()
                receivedLastDelivered.addAll(targets)
            }
            log("Delivering ${data.resolutionStatus} link to ${targets.size} listener(s)")
            for (listener in targets) safeDeliver(listener, data)
        }
    }

    private fun safeDeliver(listener: LinkLabListener, data: LinkData) {
        try {
            listener.onDynamicLinkRetrieved(Uri.parse(data.fullLink), data)
        } catch (t: Throwable) {
            logError("Listener threw while handling link", t)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Deferred deep link via Install Referrer (rule 9)
    // ------------------------------------------------------------------------------------------

    private fun deferredState(): String {
        if (preferences.getBoolean(KEY_LEGACY_CHECKED_REFERRER, false)) return STATE_DONE
        return preferences.getString(KEY_DEFERRED_STATE, STATE_PENDING) ?: STATE_PENDING
    }

    private fun markDeferredDone(reason: String) {
        log("Deferred check done: $reason")
        preferences.edit().putString(KEY_DEFERRED_STATE, STATE_DONE).apply()
    }

    private fun markDeferredTransientFailure(reason: String) {
        val attempts = preferences.getInt(KEY_DEFERRED_ATTEMPTS, 0) + 1
        log("Deferred check transient failure ($reason); attempt $attempts of $MAX_DEFERRED_ATTEMPTS")
        preferences.edit().putInt(KEY_DEFERRED_ATTEMPTS, attempts).apply()
        if (attempts >= MAX_DEFERRED_ATTEMPTS) markDeferredDone("max attempts reached")
    }

    private fun runDeferredCheck() {
        synchronized(this) {
            if (deferredState() != STATE_PENDING) return
            val now = System.currentTimeMillis()
            var firstLaunchAt = preferences.getLong(KEY_FIRST_LAUNCH_AT, 0L)
            if (firstLaunchAt == 0L) {
                firstLaunchAt = now
                preferences.edit().putLong(KEY_FIRST_LAUNCH_AT, now).apply()
            }
            val attempts = preferences.getInt(KEY_DEFERRED_ATTEMPTS, 0)
            if (attempts >= MAX_DEFERRED_ATTEMPTS || now - firstLaunchAt >= DEFERRED_WINDOW_MS) {
                markDeferredDone("attempts=$attempts, elapsed=${now - firstLaunchAt}ms")
                return
            }
            if (referrerClient != null) return // a check is already running
            referrerReconnectAttempted = false
            startReferrerConnection()
        }
    }

    private fun startReferrerConnection() {
        try {
            val client = InstallReferrerClient.newBuilder(applicationContext).build()
            referrerClient = client
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerResponse.OK -> {
                            val referrer = try {
                                client.installReferrer?.installReferrer
                            } catch (e: Exception) {
                                logError("Failed to read install referrer", e)
                                finishReferrer(client)
                                markDeferredTransientFailure("read error")
                                return
                            }
                            finishReferrer(client)
                            handleReferrer(referrer)
                        }
                        InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                        InstallReferrerResponse.PERMISSION_ERROR -> {
                            finishReferrer(client)
                            markDeferredDone("install referrer unavailable (code $responseCode)")
                        }
                        else -> {
                            // SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED, DEVELOPER_ERROR, unknown
                            finishReferrer(client)
                            markDeferredTransientFailure("install referrer response $responseCode")
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    if (!referrerReconnectAttempted) {
                        referrerReconnectAttempted = true
                        log("Install referrer service disconnected; reconnecting once")
                        try {
                            client.startConnection(this)
                            return
                        } catch (e: Exception) {
                            logError("Install referrer reconnect failed", e)
                        }
                    }
                    finishReferrer(client)
                    markDeferredTransientFailure("service disconnected")
                }
            })
        } catch (e: Exception) {
            logError("Failed to start install referrer client", e)
            referrerClient = null
            markDeferredTransientFailure("start error")
        }
    }

    private fun finishReferrer(client: InstallReferrerClient) {
        try {
            client.endConnection()
        } catch (_: Exception) {
        }
        if (referrerClient === client) referrerClient = null
    }

    private fun handleReferrer(referrer: String?) {
        val parsed = parseReferrer(referrer)
        if (parsed == null) {
            markDeferredDone("no linklab_id in install referrer")
            return
        }
        log("Install referrer contains Linklab id ${parsed.linkId}; resolving")
        fetchLink(
            parsed.linkId,
            parsed.domain,
            shortLink = null,
            isDeferred = true,
            matchType = LinkData.MATCH_INSTALL_REFERRER,
        ) { outcome ->
            when (outcome) {
                is FetchOutcome.Resolved -> {
                    markDeferredDone("deferred link resolved")
                    deliver(outcome.data)
                }
                FetchOutcome.NotFound -> markDeferredDone("deferred link not found")
                is FetchOutcome.Failed ->
                    if (outcome.transient) markDeferredTransientFailure(outcome.message)
                    else markDeferredDone("deferred link failed permanently: ${outcome.message}")
            }
        }
    }

    internal class ReferrerInfo(val linkId: String, val domain: String)

    /**
     * Extracts `linklab_id` / `domain` from a Play install-referrer string. Accepts the plain
     * `linklab_id=<id>&domain=<host>` form (URL-encoded values) and the base64-encoded form.
     * Returns null when no Linklab id is present (e.g. organic installs).
     */
    internal fun parseReferrer(referrer: String?): ReferrerInfo? {
        val raw = referrer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        parseReferrerQuery(raw)?.let { return it }
        val decoded = try {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return parseReferrerQuery(decoded)
    }

    private fun parseReferrerQuery(query: String): ReferrerInfo? {
        if (query.none { it == '=' }) return null
        return try {
            val uri = Uri.parse("?$query")
            val id = uri.getQueryParameter("linklab_id")?.takeIf { it.isNotBlank() } ?: return null
            val domain = uri.getQueryParameter("domain")?.takeIf { it.isNotBlank() } ?: LINKLAB_HOST
            ReferrerInfo(id, domain.lowercase(Locale.ROOT))
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------------------------------
    // Logging (rule 6)
    // ------------------------------------------------------------------------------------------

    private fun log(message: String) {
        if (config.debugLoggingEnabled) Log.d(TAG, message)
    }

    private fun logError(message: String, t: Throwable? = null) {
        if (config.debugLoggingEnabled) Log.e(TAG, message, t)
    }

    /** URL without its query string / fragment, safe for logs. */
    private fun redacted(uri: Uri): String =
        if (uri.isHierarchical) uri.buildUpon().clearQuery().fragment(null).build().toString()
        else "${uri.scheme}:<opaque>"

    companion object {
        private const val TAG = "LinkLab"
        private const val LINKLAB_HOST = "linklab.cc"
        private const val PREFS_NAME = "linklab_prefs"
        private const val KEY_LEGACY_CHECKED_REFERRER = "checked_install_referrer"
        private const val KEY_DEFERRED_STATE = "deferred_state"
        private const val KEY_DEFERRED_ATTEMPTS = "deferred_attempts"
        private const val KEY_FIRST_LAUNCH_AT = "first_launch_at"
        private const val STATE_PENDING = "pending"
        private const val STATE_DONE = "done"
        private const val MAX_DEFERRED_ATTEMPTS = 3
        private const val DEFERRED_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val RETRY_BASE_DELAY_MS = 500L

        /** SDK version string, e.g. `"0.1.0"`. */
        @JvmField
        val VERSION: String = BuildConfig.SDK_VERSION

        @Volatile
        private var instance: LinkLab? = null

        @JvmStatic
        fun getInstance(context: Context): LinkLab {
            return instance ?: synchronized(this) {
                instance ?: LinkLab(context.applicationContext).also { instance = it }
            }
        }

        /** Drops the singleton so each unit test starts from a clean state. Not for production use. */
        internal fun resetInstanceForTesting() {
            synchronized(this) { instance = null }
        }

        private val ISO_8601 = Regex(
            """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})?$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Parses an ISO-8601 timestamp (`2024-01-15T10:30:00Z`, `2024-01-15T10:30:00.123Z`,
         * `2024-01-15T10:30:00+02:00`) into epoch millis. Thread-safe; returns null on bad input.
         * Timestamps without a zone designator are treated as UTC.
         */
        @JvmStatic
        fun parseIso8601(value: String?): Long? {
            val m = ISO_8601.matchEntire(value?.trim() ?: return null) ?: return null
            val g = m.groupValues
            return try {
                val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
                cal.clear()
                cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
                var millis = cal.timeInMillis
                if (g[7].isNotEmpty()) millis += g[7].padEnd(3, '0').substring(0, 3).toLong()
                val zone = g[8]
                if (zone.isNotEmpty() && !zone.equals("Z", ignoreCase = true)) {
                    val sign = if (zone[0] == '-') -1 else 1
                    val digits = zone.substring(1).replace(":", "")
                    val offsetMs = (digits.substring(0, 2).toLong() * 60 + digits.substring(2, 4).toLong()) * 60_000
                    millis -= sign * offsetMs
                }
                millis
            } catch (_: Exception) {
                null
            }
        }
    }
}
