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

            // Initialize LinkLab - empty API key is acceptable
            LinkLab.getInstance(applicationContext)
                .init()
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
        try {
            // Initialize the Install Referrer client
            if (referrerClient == null) {
                referrerClient = InstallReferrerClient.newBuilder(applicationContext).build()
            }

            // Set up the connection to Google Play
            referrerClient?.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (!isActivityActive) return

                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            // Connection established, get referrer details
                            try {
                                val referrerDetails = referrerClient?.installReferrer
                                if (referrerDetails != null) {
                                    displayReferrerDetails(referrerDetails)
                                } else {
                                    updateReferrerTextView("No referrer details available (null)")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting install referrer details", e)
                                updateReferrerTextView("Error: ${e.message}")
                            } finally {
                                try {
                                    referrerClient?.endConnection()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error ending referrer connection", e)
                                }
                            }
                        }

                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                            Log.w(TAG, "Install Referrer not supported on this device")
                            updateReferrerTextView("Install Referrer not supported")
                            try {
                                referrerClient?.endConnection()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error ending referrer connection", e)
                            }
                        }

                        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                            Log.w(TAG, "Install Referrer service unavailable")
                            updateReferrerTextView("Install Referrer service unavailable")
                            try {
                                referrerClient?.endConnection()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error ending referrer connection", e)
                            }
                        }

                        else -> {
                            Log.w(TAG, "Install Referrer connection failed with code: $responseCode")
                            updateReferrerTextView("Install Referrer connection failed with code: $responseCode")
                            try {
                                referrerClient?.endConnection()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error ending referrer connection", e)
                            }
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    Log.d(TAG, "Install Referrer service disconnected")
                    // No need to update UI here as this is just a disconnection event
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Install Referrer", e)
            updateReferrerTextView("Error setting up Install Referrer: ${e.message}")
        }
    }

    private fun displayReferrerDetails(referrerDetails: ReferrerDetails?) {
        if (referrerDetails == null) {
            updateReferrerTextView("No referrer details available")
            return
        }

        try {
            var referrerUrl = referrerDetails.installReferrer
            Log.d(TAG, "Install referrer: $referrerUrl")
            updateReferrerTextView(referrerUrl)

            // Parse the referrer URL to extract parameters
            if (referrerUrl.isNotEmpty()) {
                // Look for the linklab_id parameter
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
                    Log.d(TAG, "Found linklab_id in install referrer: $linkLabId")
                    updateLinklabTextView("Found linklab_id: $linkLabId")

                    try {
                        // Delay the API call to avoid overwhelming the UI thread
                        android.os.Handler(mainLooper).postDelayed({
                            if (isActivityActive) {
                                try {
                                    // Safely encode and create URI with the link ID
                                    val encodedId = Uri.encode(linkLabId)
                                    val shortLinkUri =
                                        "https://linklab.cc/links/$encodedId".toUri() // Changed path to match format expected by API

                                    // Update UI with link info before making the API call
                                    Log.d(TAG, "Calling LinkLab with URI: $shortLinkUri")
                                    updateLinklabTextView("Found linklab_id: $linkLabId\nAttempting to get full link...")

                                    // The API call might fail because of network issues - will be handled in callbacks
                                    LinkLab.getInstance(applicationContext).getDynamicLink(shortLinkUri)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error calling LinkLab API", e)
                                    updateLinklabTextView("Found linklab_id: $linkLabId\nError: ${e.message}")
                                }
                            }
                        }, 1000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scheduling LinkLab API call", e)
                        updateLinklabTextView("Found linklab_id: $linkLabId\nError: ${e.message}")
                    }
                } else {
                    updateLinklabTextView("No linklab_id found in referrer")
                }
            } else {
                updateLinklabTextView("Empty referrer URL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing referrer details", e)
            updateReferrerTextView("Error parsing referrer details: ${e.message}")
        }
    }

    private fun updateReferrerTextView(text: String) {
        if (!isActivityActive) return

        try {
            runOnUiThread {
                try {
                    referrerTextView.text = text
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating referrerTextView", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateReferrerTextView", e)
        }
    }

    private fun updateLinklabTextView(text: String) {
        if (!isActivityActive) return

        try {
            runOnUiThread {
                try {
                    linklabTextView.text = text
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating linklabTextView", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateLinklabTextView", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            handleIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onNewIntent", e)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            Log.d(TAG, "handleIntent: intent is null")
            return
        }

        try {
            // Check if the app was launched from a dynamic link
            if (LinkLab.getInstance(applicationContext).processDynamicLink(intent)) {
                // The link is being processed, we'll get the result in the callback
                Log.d(TAG, "Processing dynamic link from intent")
            } else {
                // This was not a dynamic link, handle normal deep linking if needed
                intent.data?.let { deepLink ->
                    handleDeepLink(deepLink)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleIntent", e)
        }
    }

    // Handle your app's custom deep linking here
    private fun handleDeepLink(deepLink: Uri) {
        Log.d(TAG, "Handling regular deep link: ${deepLink}")
        updateLinklabTextView("Regular deep link: $deepLink")
    }

    // Example of programmatically getting a dynamic link
    private fun getDynamicLink(shortLink: String) {
        try {
            val shortLinkUri = Uri.parse(shortLink)
            LinkLab.getInstance(applicationContext).getDynamicLink(shortLinkUri)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getDynamicLink", e)
        }
    }

    override fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkLab.LinkData) {
        if (!isActivityActive) return
        
        Log.d(TAG, "Dynamic link retrieved: $fullLink")
        Log.d(TAG, "Link data details: id=${data.id}, domainType=${data.domainType}, domain=${data.domain}")

        try {
            // Update UI first before doing any processing
            updateLinklabTextView("LinkLab link: $fullLink\n\nData: id=${data.id}, package=${data.packageName}")

            // Show toast with delay to avoid UI thread congestion
            android.os.Handler(mainLooper).postDelayed({
                if (isActivityActive) {
                    try {
                        Toast.makeText(applicationContext, "Link received!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing toast", e)
                    }
                }
            }, 500)

            // Handle the full link on a delayed basis to avoid UI thread congestion
            android.os.Handler(mainLooper).postDelayed({
                if (isActivityActive) {
                    try {
                        handleFullLink(fullLink, data)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in delayed handling of link", e)
                    }
                }
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing dynamic link", e)
            try {
                updateLinklabTextView("Error showing link: ${e.message}")
            } catch (innerEx: Exception) {
                Log.e(TAG, "Error updating UI after link error", innerEx)
            }
        }
    }

    override fun onError(exception: Exception) {
        if (!isActivityActive) return
        
        Log.e(TAG, "Error handling dynamic link", exception)

        try {
            // Check if the error is specifically about INTERNET permission
            val errorMessage = exception.message ?: ""
            val cause = exception.cause
            val errorText = when {
                errorMessage.contains("INTERNET permission") ||
                        cause?.message?.contains("INTERNET permission") == true -> {
                    "Network error: Missing INTERNET permission or no network connection"
                }

                errorMessage.contains("Failed to retrieve link details") -> {
                    "Network error: Could not connect to LinkLab servers"
                }

                else -> {
                    "Error getting LinkLab link: ${exception.message}"
                }
            }

            // Update UI with specific error message
            updateLinklabTextView(errorText)

            // Show toast with delay to avoid overloading the UI thread
            android.os.Handler(mainLooper).postDelayed({
                if (isActivityActive) {
                    try {
                        // Don't show toast as it might add to UI clutter when there's already an error
                        // Toast.makeText(applicationContext, "Error retrieving link", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing error toast", e)
                    }
                }
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onError callback", e)
        }
    }

    private fun handleFullLink(fullLink: Uri, data: LinkLab.LinkData) {
        // Example of handling different types of links
        try {
            val path = fullLink.path ?: ""

            when {
                path.startsWith("/product/") -> {
                    // Safe substring operation with bounds checking
                    if (path.length > "/product/".length) {
                        val productId = path.substring("/product/".length)
                        openProductDetails(productId)
                    }
                }

                path.startsWith("/category/") -> {
                    if (path.length > "/category/".length) {
                        val category = path.substring("/category/".length)
                        openCategoryScreen(category)
                    }
                }

                path.startsWith("/user/") -> {
                    if (path.length > "/user/".length) {
                        val userId = path.substring("/user/".length)
                        openUserProfile(userId)
                    }
                }

                else -> {
                    // Default handling
                    openMainScreen()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleFullLink", e)
        }
    }

    // Example navigation methods
    private fun openProductDetails(productId: String) {
        Log.d(TAG, "Opening product details for ID: $productId")
        // Navigate to product details screen
    }

    private fun openCategoryScreen(category: String) {
        Log.d(TAG, "Opening category screen: $category")
        // Navigate to category screen
    }

    private fun openUserProfile(userId: String) {
        Log.d(TAG, "Opening user profile: $userId")
        // Navigate to user profile screen
    }

    private fun openMainScreen() {
        Log.d(TAG, "Opening main screen")
        // Navigate to main screen
    }

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
            // Clean up
            LinkLab.getInstance(applicationContext).removeListener(this)
            try {
                referrerClient?.endConnection()
            } catch (e: Exception) {
                Log.e(TAG, "Error ending referrer connection in onDestroy", e)
            }
            referrerClient = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
}