package cc.linklab.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cc.linklab.android.LinkLab

class MainActivity : AppCompatActivity(), LinkLab.LinkLabListener {
    companion object {
        private const val TAG = "MainActivity"
        private const val API_KEY = "your_api_key_here"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize LinkLab
        // This will automatically check for install referrer with linklab_id
        LinkLab.getInstance(this)
            .configure(API_KEY)
            .addListener(this)

        // Process any dynamic links that launched the app
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // Check if the app was launched from a dynamic link
        if (LinkLab.getInstance(this).processDynamicLink(intent)) {
            // The link is being processed, we'll get the result in the callback
            Log.d(TAG, "Processing dynamic link from intent")
        } else {
            // This was not a dynamic link, handle normal deep linking if needed
            intent.data?.let { deepLink ->
                handleDeepLink(deepLink)
            }
        }
    }

    // Handle your app's custom deep linking here
    private fun handleDeepLink(deepLink: Uri) {
        Log.d(TAG, "Handling regular deep link: ${deepLink}")
        // Your custom deep link handling code
    }

    // Example of programmatically getting a dynamic link
    private fun getDynamicLink(shortLink: String) {
        val shortLinkUri = Uri.parse(shortLink)
        LinkLab.getInstance(this).getDynamicLink(shortLinkUri)
    }

    override fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkLab.LinkData) {
        Log.d(TAG, "Dynamic link retrieved: $fullLink")
        Toast.makeText(this, "Link received: $fullLink", Toast.LENGTH_SHORT).show()

        // Now you can handle the full link based on your app's needs
        // For example, navigate to a specific screen based on the link parameters
        handleFullLink(fullLink, data)
    }

    override fun onError(exception: Exception) {
        Log.e(TAG, "Error handling dynamic link", exception)
        Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
    }

    private fun handleFullLink(fullLink: Uri, data: LinkLab.LinkData) {
        // Example of handling different types of links
        val path = fullLink.path

        when {
            path?.startsWith("/product/") == true -> {
                val productId = path.substring("/product/".length)
                openProductDetails(productId)
            }

            path?.startsWith("/category/") == true -> {
                val category = path.substring("/category/".length)
                openCategoryScreen(category)
            }

            path?.startsWith("/user/") == true -> {
                val userId = path.substring("/user/".length)
                openUserProfile(userId)
            }

            else -> {
                // Default handling
                openMainScreen()
            }
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

    override fun onDestroy() {
        super.onDestroy()
        // Clean up
        LinkLab.getInstance(this).removeListener(this)
    }
}