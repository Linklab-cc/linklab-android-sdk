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
    private val preferences: SharedPreferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private var config: LinkLabConfig = LinkLabConfig()
    private var checkedInstallReferrer = preferences.getBoolean(KEY_CHECKED_INSTALL_REFERRER, false)

    /**
     * Interface for callbacks when a dynamic link is processed.
     */
    interface LinkLabListener {
        /**
         * Called when a dynamic link has been successfully processed.
         *
         * @param fullLink The resolved full link
         * @param data Additional data about the link
         */
        fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkData)

        /**
         * Called when there was an error processing a dynamic link.
         *
         * @param exception The exception that occurred
         */
        fun onError(exception: Exception)
    }

    /**
     * Container for link data.
     */
    data class LinkData(
        val id: String,
        val fullLink: String,
        val createdAt: Long,
        val updatedAt: Long,
        val userId: String,
        val packageName: String?,
        val bundleId: String?,
        val appStoreId: String?,
        val domain: String,
        val domainType: String = "custom",
        val parameters: Map<String, String>? = null
    ) {
        companion object {
            private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            fun fromJson(json: JSONObject): LinkData {
                // Parse date strings to Long timestamps
                val createdAtStr = json.optString("createdAt")
                val updatedAtStr = json.optString("updatedAt")

                val createdAt = if (createdAtStr.isNotEmpty()) {
                    try {
                        dateFormat.parse(createdAtStr)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                } else 0L

                val updatedAt = if (updatedAtStr.isNotEmpty()) {
                    try {
                        dateFormat.parse(updatedAtStr)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                } else 0L

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
                    id = json.getString("id"),
                    fullLink = json.getString("fullLink"),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    userId = json.getString("userId"),
                    packageName = if (json.has("packageName")) json.optString("packageName", "") else null,
                    bundleId = if (json.has("bundleId")) json.optString("bundleId", "") else null,
                    appStoreId = if (json.has("appStoreId")) json.optString("appStoreId", "") else null,
                    domain = json.optString("domain", ""),
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

    /**
     * Add a listener for dynamic link events.
     *
     * @param listener The listener to add
     * @return This LinkLab instance for chaining
     */
    fun addListener(listener: LinkLabListener): LinkLab {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        return this
    }

    /**
     * Remove a listener for dynamic link events.
     *
     * @param listener The listener to remove
     * @return This LinkLab instance for chaining
     */
    fun removeListener(listener: LinkLabListener): LinkLab {
        listeners.remove(listener)
        return this
    }

    /**
     * Check if an intent contains a LinkLab dynamic link.
     *
     * @param intent The intent to check
     * @return true if the intent contains a LinkLab dynamic link, false otherwise
     */
    fun isLinkLabLink(intent: Intent?): Boolean {
        if (intent?.data == null) {
            return false
        }

        val uri = intent.data ?: return false
        val host = uri.host ?: return false

        // Check against the default domain
        if (host == REDIRECT_HOST || host.endsWith(".$REDIRECT_HOST")) {
            if (config.debugLoggingEnabled) {
                Log.d(TAG, "Link matched default domain: $host")
            }
            return true
        }
        
        // Check against custom domains
        for (domain in config.customDomains) {
            if (host == domain || host.endsWith(".$domain")) {
                if (config.debugLoggingEnabled) {
                    Log.d(TAG, "Link matched custom domain: $host (from config: $domain)")
                }
                return true
            }
        }
        
        if (config.debugLoggingEnabled) {
            Log.d(TAG, "Link did not match any domains: $host")
        }
        return false
    }

    /**
     * Process a dynamic link from an intent.
     * Listeners will be notified when the link is processed.
     *
     * @param intent The intent containing the dynamic link
     * @return true if the intent contained a dynamic link that was processed, false otherwise
     */
    fun processDynamicLink(intent: Intent?): Boolean {
        if (!isLinkLabLink(intent)) {
            if (config.debugLoggingEnabled) {
                val uri = intent?.data
                Log.d(TAG, "Not a LinkLab link: ${uri?.toString() ?: "null"}")
            }
            return false
        }

        val uri = intent?.data ?: return false
        val linkId = uri.lastPathSegment
        val domain = uri.host

        if (linkId.isNullOrEmpty()) {
            notifyError(IllegalArgumentException("Invalid dynamic link: missing link ID"))
            return false
        }

        if (config.debugLoggingEnabled) {
            Log.d(TAG, "Processing dynamic link: ${uri.toString()}")
            Log.d(TAG, "Link ID: $linkId, Domain: $domain, Query: ${uri.query}")
        }

        retrieveLinkDetails(linkId, domain)
        return true
    }

    /**
     * Process a dynamic link directly from a URI.
     * Listeners will be notified when the link is processed.
     *
     * @param shortLinkUri The URI of the short link
     */
    fun getDynamicLink(shortLinkUri: Uri) {
        val domain = shortLinkUri.host
        if (domain == null) {
            notifyError(IllegalArgumentException("Invalid dynamic link: missing domain"))
            return
        }
        
        // Check if it's a valid LinkLab domain (default or custom)
        val isDefaultDomain = domain == REDIRECT_HOST || domain.endsWith(".$REDIRECT_HOST")
        val isCustomDomain = config.customDomains.any { 
            domain == it || domain.endsWith(".$it") 
        }
        
        if (!isDefaultDomain && !isCustomDomain) {
            if (config.debugLoggingEnabled) {
                Log.d(TAG, "Invalid dynamic link domain: $domain is not in customDomains=${config.customDomains}")
            }
            notifyError(IllegalArgumentException("Invalid dynamic link: not a recognized domain"))
            return
        }

        val linkId = shortLinkUri.lastPathSegment
        if (linkId.isNullOrEmpty()) {
            notifyError(IllegalArgumentException("Invalid dynamic link: missing link ID"))
            return
        }

        retrieveLinkDetails(linkId, domain)
    }

    /**
     * Retrieve a dynamic link's details from the API.
     *
     * @param linkId The ID of the link to retrieve
     * @param domain Optional domain parameter (kept for potential logging/future use)
     */
    private fun retrieveLinkDetails(linkId: String, domain: String?) {
        Log.d(TAG, "Attempting retrieveLinkDetails. linkId: $linkId, domain: $domain")
        backgroundExecutor.execute {
            val urlBuilder = StringBuilder("$API_HOST/links/$linkId")

            // Add domain query parameter if it exists
            if (!domain.isNullOrEmpty()) {
                urlBuilder.append("?domain=").append(domain)
            }
            
            val finalUrl = urlBuilder.toString()
            Log.d(TAG, "Requesting link details from: $finalUrl")
            
            val requestBuilder = Request.Builder()
                .url(finalUrl)
                .get()

            val request = requestBuilder.build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    notifyError(Exception("Failed to retrieve link details", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Log.d(TAG, "Link details retrieving error")
                        notifyError(Exception("API error: ${response.code}"))
                        return
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrEmpty()) {
                        Log.d(TAG, "Got empty body from server")
                        notifyError(Exception("Empty response from server"))
                        return
                    }

                    try {
                        val json = JSONObject(responseBody)
                        val linkData = LinkData.fromJson(json)
                        val fullLink = linkData.fullLink.toUri()
                        Log.d(TAG, "Link details retrieved successfully $linkData")
                        notifySuccess(fullLink, linkData)
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to parse link data")
                        notifyError(Exception("Failed to parse link data", e))
                    }
                }
            })
        }
    }


    /**
     * Notify listeners of a successful dynamic link retrieval.
     *
     * @param fullLink The full link URI
     * @param data The link data
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
        
        // Create a new LinkData instance with the query parameters
        val dataWithParams = if (queryParams.isNotEmpty()) {
            // Combine with existing parameters if any
            val combinedParams = if (data.parameters != null) {
                val combined = data.parameters.toMutableMap()
                combined.putAll(queryParams)
                combined
            } else {
                queryParams
            }
            
            // Create new data object with parameters
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

    /**
     * Notify listeners of an error.
     *
     * @param exception The exception that occurred
     */
    private fun notifyError(exception: Exception) {
        Log.e(TAG, "Error processing dynamic link", exception)
        mainHandler.post {
            listeners.forEach { listener ->
                listener.onError(exception)
            }
        }
    }

    /**
     * Check for install referrer information to capture initial install source.
     * This will extract the linklab_id parameter from the referrer URL if present.
     */
    private fun checkInstallReferrer() {
        checkedInstallReferrer = true
        preferences.edit() { putBoolean(KEY_CHECKED_INSTALL_REFERRER, true) }
        
        try {
            // Initialize the Install Referrer client
            referrerClient = InstallReferrerClient.newBuilder(applicationContext).build()
            
            // Set up the connection to Google Play
            referrerClient?.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            // Connection established, get referrer details
                            try {
                                val referrerDetails = referrerClient?.installReferrer
                                parseReferrerDetails(referrerDetails)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting install referrer details", e)
                            } finally {
                                // End the connection
                                referrerClient?.endConnection()
                            }
                        }
                        
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                            Log.w(TAG, "Install Referrer not supported on this device")
                            referrerClient?.endConnection()
                        }
                        
                        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                            Log.w(TAG, "Install Referrer service unavailable")
                            referrerClient?.endConnection()
                        }
                        
                        else -> {
                            Log.w(TAG, "Install Referrer connection failed with code: $responseCode")
                            referrerClient?.endConnection()
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // Connection to the service was lost, handle if needed
                    Log.d(TAG, "Install Referrer service disconnected")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Install Referrer", e)
        }
    }

    /**
     * Parse the referrer details and extract the linklab_id, domain_type, and domain parameters if present.
     */
    private fun parseReferrerDetails(referrerDetails: ReferrerDetails?) {
        if (referrerDetails == null) {
            Log.d(TAG, "No referrer details available")
            return
        }

        try {
            val encodedReferrerUrl = referrerDetails.installReferrer
            Log.d(TAG, "Encoded install referrer: $encodedReferrerUrl")

            if (encodedReferrerUrl.isNotEmpty()) {
                // Decode the Base64 encoded referrer string
                val decodedReferrerUrl = try {
                    String(Base64.decode(encodedReferrerUrl, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Failed to decode base64 referrer string", e)
                    return
                }
                Log.d(TAG, "Decoded install referrer: $decodedReferrerUrl")

                // Parse the decoded referrer URL to extract parameters
                val params = decodedReferrerUrl.split("&")
                var linkLabId: String? = null
                var domain: String? = null

                // Look for the linklab parameters
                for (param in params) {
                    val keyValue = param.split("=")
                    if (keyValue.size == 2) {
                        when (keyValue[0]) {
                            "linklab_id" -> linkLabId = keyValue[1]
                            "domain" -> domain = keyValue[1]
                        }
                    }
                }

                // If we found a linklab_id, retrieve the link details
                if (!linkLabId.isNullOrEmpty()) {
                    Log.d(TAG, "Found linklab_id in install referrer: $linkLabId")
                    Log.d(TAG, "Domain: $domain")
                    retrieveLinkDetails(linkLabId, domain)
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

        /**
         * Get the singleton instance of LinkLab.
         *
         * @param context The application context
         * @return The LinkLab instance
         */
        fun getInstance(context: Context): LinkLab {
            return instance ?: synchronized(this) {
                instance ?: LinkLab(context.applicationContext).also { instance = it }
            }
        }
    }
}