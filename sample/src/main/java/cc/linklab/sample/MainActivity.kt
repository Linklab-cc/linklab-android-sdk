package cc.linklab.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import cc.linklab.android.LinkLab
import cc.linklab.android.LinkLabConfig
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails

class MainActivity : AppCompatActivity(), LinkLab.LinkLabListener {
    companion object {
        private const val TAG = "MainActivity"
    }

    // Lazy initialize TextViews to avoid null issues on first access
    private val referrerTextView: TextView by lazy { findViewById(R.id.referrerTextView) }
    private val linklabTextView: TextView by lazy { findViewById(R.id.linklabTextView) }

    private var referrerClient: InstallReferrerClient? = null
    private var isActivityActive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            // Set initial default texts first
            updateReferrerTextView("Checking install referrer...")
            updateLinklabTextView("Waiting for LinkLab data...")

            // Initialize LinkLab with configuration
            val config = LinkLabConfig(
                customDomains = listOf("app.potje.tech", "demo.linklab.cc"),
                debugLoggingEnabled = true
            )

            LinkLab.getInstance(applicationContext)
                .init(config)
                .addListener(this)

            // Process any dynamic links that launched the app
            handleIntent(intent)

            // Get install referrer directly for display after a longer delay
            android.os.Handler(mainLooper).postDelayed({
                if (isActivityActive) {
                    getInstallReferrer()
                }
            }, 1500) // Increased delay to 1.5 seconds

        } catch (e: Exception) {
            Log.e(TAG, "Error during onCreate", e)
            updateLinklabTextView("Error initializing: ${e.message}")
        }
    }

    private fun getInstallReferrer() {
        // ... (Код getInstallReferrer без змін, він не впливає на LinkData)
        // Залиште стару реалізацію, вона коректна
        try {
            if (referrerClient == null) {
                referrerClient = InstallReferrerClient.newBuilder(applicationContext).build()
            }
            referrerClient?.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (!isActivityActive) return
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            try {
                                val referrerDetails = referrerClient?.installReferrer
                                displayReferrerDetails(referrerDetails)
                            } catch (e: Exception) {
                                updateReferrerTextView("Error: ${e.message}")
                            } finally {
                                try { referrerClient?.endConnection() } catch (e: Exception) {}
                            }
                        }
                        else -> {
                            updateReferrerTextView("Install Referrer failed: $responseCode")
                            try { referrerClient?.endConnection() } catch (e: Exception) {}
                        }
                    }
                }
                override fun onInstallReferrerServiceDisconnected() {}
            })
        } catch (e: Exception) {
            updateReferrerTextView("Error setting up Install Referrer: ${e.message}")
        }
    }

    private fun displayReferrerDetails(referrerDetails: ReferrerDetails?) {
        // ... (Код displayReferrerDetails без змін, він тільки парсить referrer string)
        // Залиште стару реалізацію
        if (referrerDetails == null) {
            updateReferrerTextView("No referrer details available")
            return
        }
        try {
            var referrerUrl = referrerDetails.installReferrer
            updateReferrerTextView(referrerUrl)
            if (referrerUrl.isNotEmpty()) {
                val params = referrerUrl.split("&")
                var linkLabId: String? = null
                for (param in params) {
                    val keyValue = param.split("=")
                    if (keyValue.size == 2 && keyValue[0] == "linklab_id") {
                        linkLabId = keyValue[1]
                        break
                    }
                }
                if (!linkLabId.isNullOrEmpty()) {
                    updateLinklabTextView("Found linklab_id: $linkLabId")
                    android.os.Handler(mainLooper).postDelayed({
                        if (isActivityActive) {
                            try {
                                val encodedId = Uri.encode(linkLabId)
                                val shortLinkUri = "https://linklab.cc/links/$encodedId".toUri()
                                updateLinklabTextView("Found linklab_id: $linkLabId\nAttempting to get full link...")
                                LinkLab.getInstance(applicationContext).getDynamicLink(shortLinkUri)
                            } catch (e: Exception) {
                                updateLinklabTextView("Error: ${e.message}")
                            }
                        }
                    }, 1000)
                } else {
                    updateLinklabTextView("No linklab_id found in referrer")
                }
            } else {
                updateLinklabTextView("Empty referrer URL")
            }
        } catch (e: Exception) {
            updateReferrerTextView("Error: ${e.message}")
        }
    }

    private fun updateReferrerTextView(text: String) {
        if (!isActivityActive) return
        try {
            runOnUiThread { referrerTextView.text = text }
        } catch (e: Exception) {}
    }

    private fun updateLinklabTextView(text: String) {
        if (!isActivityActive) return
        try {
            runOnUiThread { linklabTextView.text = text }
        } catch (e: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        try {
            if (LinkLab.getInstance(applicationContext).processDynamicLink(intent)) {
                Log.d(TAG, "Processing dynamic link from intent")
            } else {
                intent.data?.let { handleDeepLink(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleIntent", e)
        }
    }

    private fun handleDeepLink(deepLink: Uri) {
        Log.d(TAG, "Handling regular deep link: ${deepLink}")
        updateLinklabTextView("Regular deep link: $deepLink")
    }

    override fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkLab.LinkData) {
        if (!isActivityActive) return

        Log.d(TAG, "Dynamic link retrieved: $fullLink")
        // Use Elvis operator or string templates for nullable ID
        Log.d(TAG, "Link data details: id=${data.id ?: "unrecognized"}, domain=${data.domain}, type=${data.domainType}")

        try {
            // Build parameter display string
            val paramsDisplay = data.parameters
                ?.takeIf { it.isNotEmpty() }
                ?.entries
                ?.joinToString(", ") { "${it.key}=${it.value}" }
                ?.let { "\n\nParameters: $it" }
                ?: "\n\nNo parameters"

            // Handle unrecognized case in UI text
            val idDisplay = data.id ?: "unrecognized"

            updateLinklabTextView("LinkLab link: $fullLink\\n\\nData: id=$idDisplay, domain=${data.domain}, type=${data.domainType}$paramsDisplay")

            android.os.Handler(mainLooper).postDelayed({
                if (isActivityActive) {
                    try {
                        Toast.makeText(applicationContext, "Link received!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {}
                }
            }, 500)

            android.os.Handler(mainLooper).postDelayed({
                if (isActivityActive) {
                    try {
                        handleFullLink(fullLink, data)
                    } catch (e: Exception) {}
                }
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing dynamic link", e)
            try {
                updateLinklabTextView("Error showing link: ${e.message}")
            } catch (innerEx: Exception) {}
        }
    }

    override fun onError(exception: Exception) {
        // With Fail-open, this will happen much less frequently (mostly for misuse of SDK, not link errors)
        if (!isActivityActive) return
        Log.e(TAG, "Error handling dynamic link", exception)
        try {
            updateLinklabTextView("SDK Error: ${exception.message}")
        } catch (e: Exception) {}
    }

    private fun handleFullLink(fullLink: Uri, data: LinkLab.LinkData) {
        try {
            val path = fullLink.path ?: ""

            val queryParams = fullLink.queryParameterNames.associate { it to (fullLink.getQueryParameter(it) ?: "") }
            val allParams = mutableMapOf<String, String>()

            allParams.putAll(queryParams)
            data.parameters?.let { allParams.putAll(it) }

            if (allParams.isNotEmpty()) {
                Log.d(TAG, "All parameters for link handling: $allParams")
            }

            // ... (Решта логіки навігації залишається без змін)
            when {
                path.startsWith("/product/") -> {
                    if (path.length > "/product/".length) {
                        openProductDetails(path.substring("/product/".length))
                    }
                }
                else -> {
                    openMainScreen()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleFullLink", e)
        }
    }

    private fun openProductDetails(productId: String) {}
    private fun openCategoryScreen(category: String) {}
    private fun openUserProfile(userId: String) {}
    private fun openMainScreen() {}

    override fun onResume() {
        super.onResume()
        isActivityActive = true
    }

    override fun onPause() {
        super.onPause()
        isActivityActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isActivityActive = false
        try {
            LinkLab.getInstance(applicationContext).removeListener(this)
            try { referrerClient?.endConnection() } catch (e: Exception) {}
            referrerClient = null
        } catch (e: Exception) {}
    }
}