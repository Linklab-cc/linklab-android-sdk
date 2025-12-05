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
 * This holds settings like custom domains and debug mode.
 */
class LinkLabConfig(
    val customDomains: List<String> = listOf(),
    val debugLoggingEnabled: Boolean = false,
    val networkTimeout: Double = 30.0,
    val networkRetryCount: Int = 3
)

/**
 * LinkLab is a library for handling deep links for Android applications.
 * It handles the logic of checking if a link belongs to the service or not.
 */
class LinkLab private constructor(private val applicationContext: Context) {
    // Tool for making network requests
    private val httpClient = OkHttpClient()

    // Background worker to keep the main app smooth
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor()

    // Handler to send results back to the main screen (UI)
    private val mainHandler = Handler(Looper.getMainLooper())

    // List of parts of the app listening for link results
    private val listeners = mutableListOf<LinkLabListener>()

    private var referrerClient: InstallReferrerClient? = null
    private val preferences: SharedPreferences =
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val processedLinkIds = mutableSetOf<String>()
    private var config: LinkLabConfig = LinkLabConfig()
    private var checkedInstallReferrer = preferences.getBoolean(KEY_CHECKED_INSTALL_REFERRER, false)

    /**
     * Interface for callbacks when a deep link is processed.
     * This is how the app receives the result.
     */
    interface LinkLabListener {
        /**
         * Called when a link is processed.
         * * @param fullLink The final URL (either resolved or the original one if unrecognized).
         * @param data Object containing details about the link.
         */
        fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkData)

        /**
         * Called ONLY if a critical error occurs that cannot be handled by "Fail-open".
         */
        fun onError(exception: Exception)
    }

    /**
     * Container for link data.
     * Describes the properties of a link.
     */
    data class LinkData(
        val id: String?,
        val fullLink: String,
        val createdAt: Long?,
        val updatedAt: Long?,
        val userId: String?,
        val packageName: String?,
        val bundleId: String?,
        val appStoreId: String?,
        val domain: String?,
        val domainType: String, // Type of domain (e.g., "custom", "default", or "unrecognized")
        val parameters: Map<String, String>? = null
    ) {
        companion object {
            private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            // --- FAIL-OPEN HELPER ---
            // This creates a "safe" object when we don't know the link or the API fails.
            // It allows the app to continue working with the original link.
            fun unrecognized(uri: Uri): LinkData {
                return LinkData(
                    id = null,
                    fullLink = uri.toString(), // We just return the original link
                    createdAt = null,
                    updatedAt = null,
                    userId = null,
                    packageName = null,
                    bundleId = null,
                    appStoreId = null,
                    domain = uri.host,
                    domainType = "unrecognized", // Tag it as unrecognized
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
                    id = json.optString("id"),
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
     */
    fun init(config: LinkLabConfig = LinkLabConfig()): LinkLab {
        this.config = config

        if (config.debugLoggingEnabled) {
            Log.d(TAG, "Initializing LinkLab with config: customDomains=${config.customDomains}")
        }

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
     * This checks if the link domain matches our service or custom domains.
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
     * MAIN ENTRY POINT: Process a dynamic link from an intent.
     * * Strategy:
     * 1. If it IS a LinkLab link -> Fetch details from API.
     * 2. If it is NOT a LinkLab link -> Return it immediately as "unrecognized".
     * * This ensures the app always gets a result, even for external links.
     */
    fun processDynamicLink(intent: Intent?): Boolean {
        // Step 1: Get the link (URI) from the intent
        val uri = intent?.data

        // If no link exists, we can't do anything.
        if (uri == null) {
            return false
        }

        // Step 2: Check if this link belongs to LinkLab or our Custom Domains
        if (!isLinkLabLink(intent)) {
            // --- FAIL-OPEN LOGIC ---
            // The link exists, but it is NOT ours (e.g., google.com or another deep link).
            // We should not ignore it. We must pass it back to the app so the app can handle it.

            if (config.debugLoggingEnabled) {
                Log.d(TAG, "External link detected (not LinkLab): $uri. Passing through as unrecognized.")
            }

            // Create a wrapper object marked as "unrecognized" and send it to listeners
            notifySuccess(uri, LinkData.unrecognized(uri))

            // Return true to indicate we handled the intent successfully
            return true
        }

        // Step 3: It IS a LinkLab link. Proceed to fetch details from the server.
        retrieveLinkDetails(uri)
        return true
    }

    /**
     * Process a dynamic link directly from a URI object (manual call).
     */
    fun getDynamicLink(shortLinkUri: Uri) {
        retrieveLinkDetails(shortLinkUri)
    }

    /**
     * Retrieve a dynamic link's details from the API.
     * Uses "Fail-open" strategy: if network fails, we act as if the link is unrecognized.
     */
    private fun retrieveLinkDetails(uri: Uri) {
        val linkId = uri.lastPathSegment
        val domain = uri.host

        // Validation: If format is wrong, don't crash. Just return the link as is.
        if (linkId.isNullOrEmpty() || domain.isNullOrEmpty()) {
            if (config.debugLoggingEnabled) {
                Log.d(TAG, "Invalid link format. Treating as unrecognized: $uri")
            }
            notifySuccess(uri, LinkData.unrecognized(uri))
            return
        }

        // Check cache to avoid duplicate processing
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
                // Network Error (e.g., no internet)
                override fun onFailure(call: Call, e: IOException) {
                    Log.d(TAG, "Network failed: ${e.message}. Failing open.")
                    // SAFETY: Return the original link so the app isn't blocked.
                    notifySuccess(uri, LinkData.unrecognized(uri))
                }

                // Server Response
                override fun onResponse(call: Call, response: Response) {
                    // Handle server errors (e.g., 404 Not Found, 500 Server Error)
                    if (!response.isSuccessful) {
                        Log.d(TAG, "API error: ${response.code}. Failing open.")
                        // SAFETY: Return the original link.
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
                        // SUCCESS: Parse the JSON from the server
                        val json = JSONObject(responseBody)
                        val linkData = LinkData.fromJson(json)
                        val fullLink = linkData.fullLink.toUri()

                        // Mark as processed
                        linkData.id?.let { processedLinkIds.add(it) }

                        Log.d(TAG, "Link details retrieved successfully")
                        notifySuccess(fullLink, linkData)
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to parse link data: ${e.message}. Failing open.")
                        // SAFETY: If JSON is bad, return original link.
                        notifySuccess(uri, LinkData.unrecognized(uri))
                    }
                }
            })
        }
    }

    /**
     * Logic for Install Referrer (Internal Use).
     * This tracks where the app install came from.
     */
    private fun retrieveLinkDetailsForReferrer(linkId: String, domain: String) {
        if (processedLinkIds.contains(linkId)) return

        backgroundExecutor.execute {
            val url = "$API_HOST/links/$linkId?domain=$domain"
            val request = Request.Builder().url(url).get().build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Referrer fetch failed", e)
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

    /**
     * Helper to send success result to the main thread (UI).
     */
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

        // Merge existing params with URL params
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

        // Send to listeners on Main Thread
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

    /**
     * Setup Install Referrer Client.
     */
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