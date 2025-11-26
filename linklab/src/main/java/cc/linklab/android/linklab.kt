package cc.linklab.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Configuration class for LinkLab SDK.
 */
class LinkLabConfig(
    val customDomains: List<String> = listOf(),
    val debugLoggingEnabled: Boolean = false,
    val networkTimeout: Double = 30.0,
    val networkRetryCount: Int = 3
)

/**
 * LinkLab is a library for handling dynamic links for Android applications.
 * It can retrieve a full link from a short link, either automatically from an Intent
 * or programmatically by providing a short link.
 */
class LinkLab private constructor(private val applicationContext: Context) {
    private val httpClient = OkHttpClient()
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<LinkLabListener>()
    private var referrerClient: InstallReferrerClient? = null
    private val preferences: SharedPreferences =
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val processedLinkIds = mutableSetOf<String>()
    private var config: LinkLabConfig = LinkLabConfig()
    private var checkedInstallReferrer = preferences.getBoolean(KEY_CHECKED_INSTALL_REFERRER, false)

    /**
     * Interface for callbacks when a dynamic link is processed.
     */
    interface LinkLabListener {
        /**
         * Called when a dynamic link has been successfully processed.
         *
         * @param fullLink The resolved full link (or raw link if unrecognized)
         * @param data Additional data about the link
         */
        fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkData)

        /**
         * Called when there was an error processing a dynamic link.
         * Note: With fail-open strategy, most errors will now result in onDynamicLinkRetrieved
         * with an "unrecognized" LinkData object.
         *
         * @param exception The exception that occurred
         */
        fun onError(exception: Exception)
    }

    /**
     * Container for link data.
     */
    data class LinkData(
        val id: String?, // Changed to nullable
        val fullLink: String,
        val createdAt: Long?, // Changed to nullable
        val updatedAt: Long?, // Changed to nullable
        val userId: String?, // Changed to nullable
        val packageName: String?,
        val bundleId: String?,
        val appStoreId: String?,
        val domain: String?,
        val domainType: String, // Non-nullable
        val parameters: Map<String, String>? = null
    ) {
        companion object {
            private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            // Factory method for unrecognized links (Fail-open)
            fun unrecognized(uri: Uri): LinkData {
                return LinkData(
                    id = null,
                    fullLink = uri.toString(),
                    createdAt = null,
                    updatedAt = null,
                    userId = null,
                    packageName = null,
                    bundleId = null,
                    appStoreId = null,
                    domain = uri.host,
                    domainType = "unrecognized",
                    parameters = null
                )
            }

            fun fromJson(json: JSONObject): LinkData {
                // Parse date strings to Long timestamps
                val createdAtStr = json.optString("createdAt")
                val updatedAtStr = json.optString("updatedAt")

                val createdAt = if (createdAtStr.isNotEmpty()) {
                    try {
                        dateFormat.parse(createdAtStr)?.time
                    } catch (e: Exception) {
                        null
                    }
                } else null

                val updatedAt = if (updatedAtStr.isNotEmpty()) {
                    try {
                        dateFormat.parse(updatedAtStr)?.time
                    } catch (e: Exception) {
                        null
                    }
                } else null

                // Parse parameters if they exist
                val params = if (json.has("parameters")) {
                    try {
                        val paramsObj = json.getJSONObject("parameters")
                        val paramMap = mutableMapOf<String, String>()
                        paramsObj.keys().forEach { key ->
                            paramMap[key] = paramsObj.optString(key, "")
                        }
                        paramMap
                    } catch (e: Exception) {
                        null
                    }
                } else null

                return LinkData(
                    id = json.optString("id"), // Use optString just in case
                    fullLink = json.getString("fullLink"),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    userId = json.optString("userId"),
                    packageName = json.optString("packageName").takeIf { it.isNotEmpty() },
                    bundleId = json.optString("bundleId").takeIf { it.isNotEmpty() },
                    appStoreId = json.optString("appStoreId").takeIf { it.isNotEmpty() },
                    domain = json.optString("domain"),
                    domainType = json.optString("domainType", "custom"),
                    parameters = params
                )
            }
        }
    }

    /**
     * Init LinkLab
     *
     * @param config Configuration for the LinkLab SDK (optional)
     * @return This LinkLab instance for chaining
     */
    fun init(config: LinkLabConfig = LinkLabConfig()): LinkLab {
        // Set the configuration
        this.config = config

        // Log the configuration
        if (config.debugLoggingEnabled) {
            Log.d(TAG, "Initializing LinkLab with config: customDomains=${config.customDomains}")
        }

        // Check for install referrer if this is the first initialization
        if (!checkedInstallReferrer) {
            checkInstallReferrer()
        }

        return this
    }

    fun addListener(listener: LinkLabListener): LinkLab {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        return this
    }

    fun removeListener(listener: LinkLabListener): LinkLab {
        listeners.remove(listener)
        return this
    }

    /**
     * Check if an intent contains a LinkLab dynamic link.
     */
    fun isLinkLabLink(intent: Intent?): Boolean {
        if (intent?.data == null) {
            return false
        }

        val uri = intent.data ?: return false
        val host = uri.host ?: return false

        // Check against the default domain
        if (host == REDIRECT_HOST || host.endsWith(".$REDIRECT_HOST")) {
            return true
        }

        // Check against custom domains
        for (domain in config.customDomains) {
            if (host == domain || host.endsWith(".$domain")) {
                return true
            }
        }
        return false
    }

    /**
     * Process a dynamic link from an intent.
     */
    fun processDynamicLink(intent: Intent?): Boolean {
        // Even if validation fails, we might want to pass it through if it looks like a deep link,
        // but traditionally we only check configured domains.
        // If it's NOT a LinkLab link (by domain), we return false so the app handles it as a normal deep link.
        if (!isLinkLabLink(intent)) {
            if (config.debugLoggingEnabled) {
                val uri = intent?.data
                Log.d(TAG, "Not a LinkLab link: ${uri?.toString() ?: "null"}")
            }
            return false
        }

        val uri = intent?.data ?: return false

        // Pass the URI to retrieval
        retrieveLinkDetails(uri)
        return true
    }

    /**
     * Process a dynamic link directly from a URI.
     */
    fun getDynamicLink(shortLinkUri: Uri) {
        retrieveLinkDetails(shortLinkUri)
    }

    /**
     * Retrieve a dynamic link's details from the API using a URI object.
     * This method implements the "Fail-open" strategy.
     *
     * @param uri The full URI of the short link
     */
    private fun retrieveLinkDetails(uri: Uri) {
        val linkId = uri.lastPathSegment
        val domain = uri.host

        // 1. Validation: If no link ID or domain, fail open immediately
        if (linkId.isNullOrEmpty() || domain.isNullOrEmpty()) {
            if (config.debugLoggingEnabled) {
                Log.d(TAG, "Invalid link format. Treating as unrecognized: $uri")
            }
            notifySuccess(uri, LinkData.unrecognized(uri))
            return
        }

        // 2. Check cache
        if (processedLinkIds.contains(linkId)) {
            if (config.debugLoggingEnabled) {
                Log.d(TAG, "Link ID $linkId has already been processed. Skipping.")
            }
            return
        }

        Log.d(TAG, "Attempting retrieveLinkDetails. linkId: $linkId, domain: $domain")

        backgroundExecutor.execute {
            val urlBuilder = StringBuilder("$API_HOST/links/$linkId")
            urlBuilder.append("?domain=").append(domain)

            val finalUrl = urlBuilder.toString()
            Log.d(TAG, "Requesting link details from: $finalUrl")

            val request = Request.Builder().url(finalUrl).get().build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.d(TAG, "Network failed: ${e.message}. Failing open.")
                    // FAIL OPEN: Return unrecognized
                    notifySuccess(uri, LinkData.unrecognized(uri))
                }

                override fun onResponse(call: Call, response: Response) {
                    // Handle non-successful responses (404, 500, etc.)
                    if (!response.isSuccessful) {
                        Log.d(TAG, "API error: ${response.code}. Failing open.")
                        // FAIL OPEN: Return unrecognized
                        notifySuccess(uri, LinkData.unrecognized(uri))
                        return
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrEmpty()) {
                        Log.d(TAG, "Empty body. Failing open.")
                        notifySuccess(uri, LinkData.unrecognized(uri))
                        return
                    }

                    try {
                        val json = JSONObject(responseBody)
                        val linkData = LinkData.fromJson(json)
                        val fullLink = linkData.fullLink.toUri()

                        // Add to processed set only if valid ID exists
                        linkData.id?.let { processedLinkIds.add(it) }

                        Log.d(TAG, "Link details retrieved successfully")
                        notifySuccess(fullLink, linkData)
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to parse link data: ${e.message}. Failing open.")
                        // FAIL OPEN: Parsing failed, return unrecognized
                        notifySuccess(uri, LinkData.unrecognized(uri))
                    }
                }
            })
        }
    }

    /**
     * Overload for Install Referrer (internal use).
     * Install Referrer logic does not have a "Fail-open" to a deep link,
     * because there is no user-facing deep link involved.
     */
    private fun retrieveLinkDetailsForReferrer(linkId: String, domain: String) {
        if (processedLinkIds.contains(linkId)) return

        backgroundExecutor.execute {
            val url = "$API_HOST/links/$linkId?domain=$domain"
            val request = Request.Builder().url(url).get().build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Referrer fetch failed", e)
                    // Silent failure for referrer
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        try {
                            val body = response.body?.string() ?: return
                            val json = JSONObject(body)
                            val linkData = LinkData.fromJson(json)
                            val fullLink = linkData.fullLink.toUri()

                            linkData.id?.let { processedLinkIds.add(it) }
                            notifySuccess(fullLink, linkData)
                        } catch (e: Exception) {
                            Log.e(TAG, "Referrer parse failed", e)
                        }
                    }
                }
            })
        }
    }

    private fun notifySuccess(fullLink: Uri, data: LinkData) {
        // Extract query parameters from the full link URL
        val queryParams = mutableMapOf<String, String>()
        if (fullLink.query != null) {
            val query = fullLink.query ?: ""
            val pairs = query.split("&")
            for (pair in pairs) {
                val idx = pair.indexOf("=")
                if (idx > 0) {
                    val key = pair.substring(0, idx)
                    val value = pair.substring(idx + 1)
                    queryParams[key] = value
                }
            }
        }

        // Create a new LinkData instance with the query parameters if needed
        val dataWithParams = if (queryParams.isNotEmpty()) {
            val combinedParams = if (data.parameters != null) {
                val combined = data.parameters.toMutableMap()
                combined.putAll(queryParams)
                combined
            } else {
                queryParams
            }
            data.copy(parameters = combinedParams)
        } else {
            data
        }

        mainHandler.post {
            listeners.forEach { listener ->
                listener.onDynamicLinkRetrieved(fullLink, dataWithParams)
            }
        }
    }

    private fun notifyError(exception: Exception) {
        Log.e(TAG, "Error processing dynamic link", exception)
        mainHandler.post {
            listeners.forEach { listener ->
                listener.onError(exception)
            }
        }
    }

    private fun checkInstallReferrer() {
        checkedInstallReferrer = true
        preferences.edit() { putBoolean(KEY_CHECKED_INSTALL_REFERRER, true) }

        try {
            referrerClient = InstallReferrerClient.newBuilder(applicationContext).build()
            referrerClient?.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        try {
                            val referrerDetails = referrerClient?.installReferrer
                            parseReferrerDetails(referrerDetails)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error getting install referrer details", e)
                        } finally {
                            referrerClient?.endConnection()
                        }
                    } else {
                        referrerClient?.endConnection()
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {}
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Install Referrer", e)
        }
    }

    private fun parseReferrerDetails(referrerDetails: ReferrerDetails?) {
        if (referrerDetails == null) return

        try {
            val encodedReferrerUrl = referrerDetails.installReferrer
            if (encodedReferrerUrl.isNotEmpty()) {
                val decodedReferrerUrl = try {
                    String(Base64.decode(encodedReferrerUrl, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) {
                    return
                }

                val params = decodedReferrerUrl.split("&")
                var linkLabId: String? = null
                var domain: String? = null

                for (param in params) {
                    val keyValue = param.split("=")
                    if (keyValue.size == 2) {
                        when (keyValue[0]) {
                            "linklab_id" -> linkLabId = keyValue[1]
                            "domain" -> domain = keyValue[1]
                        }
                    }
                }

                if (!linkLabId.isNullOrEmpty() && !domain.isNullOrEmpty()) {
                    retrieveLinkDetailsForReferrer(linkLabId, domain!!)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing referrer details", e)
        }
    }

    companion object {
        private const val TAG = "LinkLab"
        private const val API_HOST = "https://linklab.cc"
        private const val REDIRECT_HOST = "linklab.cc"
        private const val PREFS_NAME = "linklab_prefs"
        private const val KEY_CHECKED_INSTALL_REFERRER = "checked_install_referrer"

        @Volatile
        private var instance: LinkLab? = null

        fun getInstance(context: Context): LinkLab {
            return instance ?: synchronized(this) {
                instance ?: LinkLab(context.applicationContext).also { instance = it }
            }
        }
    }
}