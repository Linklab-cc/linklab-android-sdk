package cc.linklab.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
        val domainType: String,
        val domain: String
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

                return LinkData(
                    id = json.getString("id"),
                    fullLink = json.getString("fullLink"),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    userId = json.getString("userId"),
                    packageName = if (json.has("packageName")) json.optString("packageName", "") else null,
                    bundleId = if (json.has("bundleId")) json.optString("bundleId", "") else null,
                    appStoreId = if (json.has("appStoreId")) json.optString("appStoreId", "") else null,
                    domainType = json.optString("domainType", ""),
                    domain = json.optString("domain", "")
                )
            }
        }
    }
    /**
     * Init LinkLab
     *
     * @return This LinkLab instance for chaining
     */
    fun init(): LinkLab {
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

        return host == REDIRECT_HOST || host.endsWith(".$REDIRECT_HOST")
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
            return false
        }

        val uri = intent?.data ?: return false
        val linkId = uri.lastPathSegment

        if (linkId.isNullOrEmpty()) {
            notifyError(IllegalArgumentException("Invalid dynamic link: missing link ID"))
            return false
        }

        retrieveLinkDetails(linkId)
        return true
    }

    /**
     * Process a dynamic link directly from a URI.
     * Listeners will be notified when the link is processed.
     *
     * @param shortLinkUri The URI of the short link
     */
    fun getDynamicLink(shortLinkUri: Uri) {
        val host = shortLinkUri.host
        if (host == null || (host != REDIRECT_HOST && !host.endsWith(".$REDIRECT_HOST"))) {
            notifyError(IllegalArgumentException("Invalid dynamic link: not a LinkLab domain"))
            return
        }

        val linkId = shortLinkUri.lastPathSegment
        if (linkId.isNullOrEmpty()) {
            notifyError(IllegalArgumentException("Invalid dynamic link: missing link ID"))
            return
        }

        retrieveLinkDetails(linkId)
    }

    /**
     * Retrieve a dynamic link's details from the API.
     *
     * @param linkId The ID of the link to retrieve
     */
    private fun retrieveLinkDetails(linkId: String) {
        backgroundExecutor.execute {
            val requestBuilder = Request.Builder()
                .url("$API_HOST/links/$linkId")
                .get()

            val request = requestBuilder.build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    notifyError(Exception("Failed to retrieve link details", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        notifyError(Exception("API error: ${response.code}"))
                        return
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrEmpty()) {
                        notifyError(Exception("Empty response from server"))
                        return
                    }

                    try {
                        val json = JSONObject(responseBody)
                        val linkData = LinkData.fromJson(json)
                        val fullLink = linkData.fullLink.toUri()

                        notifySuccess(fullLink, linkData)
                    } catch (e: Exception) {
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
        mainHandler.post {
            listeners.forEach { listener ->
                listener.onDynamicLinkRetrieved(fullLink, data)
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
     * Parse the referrer details and extract the linklab_id parameter if present.
     */
    private fun parseReferrerDetails(referrerDetails: ReferrerDetails?) {
        if (referrerDetails == null) {
            Log.d(TAG, "No referrer details available")
            return
        }
        
        try {
            val referrerUrl = referrerDetails.installReferrer
            Log.d(TAG, "Install referrer: $referrerUrl")
            
            // Parse the referrer URL to extract parameters
            if (referrerUrl.isNotEmpty()) {
                // The referrer string is usually URL encoded, so we need to parse it
                val params = referrerUrl.split("&")
                var linkLabId: String? = null
                
                // Look for the linklab_id parameter
                for (param in params) {
                    val keyValue = param.split("=")
                    if (keyValue.size == 2 && keyValue[0] == "linklab_id") {
                        linkLabId = keyValue[1]
                        break
                    }
                }
                
                // If we found a linklab_id, retrieve the link details
                if (!linkLabId.isNullOrEmpty()) {
                    Log.d(TAG, "Found linklab_id in install referrer: $linkLabId")
                    retrieveLinkDetails(linkLabId)
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