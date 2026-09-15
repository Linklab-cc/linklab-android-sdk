package cc.linklab.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cc.linklab.android.LinkLab
import cc.linklab.android.LinkLabConfig
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

/**
 * Minimal integration example for the Linklab Android SDK.
 *
 * - Initialises the SDK once with a [LinkLabConfig].
 * - Passes every incoming intent to [LinkLab.processDynamicLink]; when it returns `false`
 *   the intent is not a Linklab link and the app handles it itself.
 * - Receives resolved / unrecognized / failed links through [LinkLab.LinkLabListener].
 *
 * The install-referrer section only *displays* the raw Play Store referrer string for
 * debugging. The SDK reads and resolves the referrer on its own during the first launch.
 */
class MainActivity : AppCompatActivity(), LinkLab.LinkLabListener {

    private val referrerTextView: TextView by lazy { findViewById(R.id.referrerTextView) }
    private val linklabTextView: TextView by lazy { findViewById(R.id.linklabTextView) }

    private var referrerClient: InstallReferrerClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        referrerTextView.text = "Reading install referrer..."
        linklabTextView.text = "Waiting for a Linklab link..."

        val config = LinkLabConfig.Builder()
            .customDomains(listOf("demo.linklab.cc"))
            .debugLoggingEnabled(true)
            .build()

        LinkLab.getInstance(this)
            .init(config)
            .addListener(this)

        handleIntent(intent)
        showRawInstallReferrer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (LinkLab.getInstance(this).processDynamicLink(intent)) {
            Log.d(TAG, "Linklab link is being processed")
        } else {
            // Not a Linklab link (or no data at all): regular deep-link handling goes here.
            intent?.data?.let { linklabTextView.text = "Non-Linklab deep link: $it" }
        }
    }

    // LinkLab.LinkLabListener -------------------------------------------------------------

    override fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkLab.LinkData) {
        val params = data.parameters.entries.joinToString(", ") { "${it.key}=${it.value}" }
        linklabTextView.text = buildString {
            appendLine("fullLink: ${data.fullLink}")
            appendLine("shortLink: ${data.shortLink}")
            appendLine("id: ${data.id}")
            appendLine("status: ${data.resolutionStatus}")
            appendLine("domain: ${data.domain} (${data.domainType})")
            appendLine("deferred: ${data.isDeferred}, matchType: ${data.matchType}")
            if (data.errorMessage != null) appendLine("error: ${data.errorMessage}")
            append("parameters: ${params.ifEmpty { "(none)" }}")
        }
        route(fullLink)
    }

    override fun onError(exception: Exception) {
        linklabTextView.text = "SDK error: ${exception.message}"
    }

    private fun route(fullLink: Uri) {
        val path = fullLink.path.orEmpty()
        when {
            path.startsWith("/product/") -> Log.d(TAG, "open product ${path.removePrefix("/product/")}")
            else -> Log.d(TAG, "open main screen")
        }
    }

    // Debug display of the raw referrer -----------------------------------------------------

    private fun showRawInstallReferrer() {
        val client = InstallReferrerClient.newBuilder(this).build()
        referrerClient = client
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                referrerTextView.text = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    runCatching { client.installReferrer.installReferrer }.getOrElse { "error: ${it.message}" }
                } else {
                    "Install Referrer unavailable (code $responseCode)"
                }
                runCatching { client.endConnection() }
                referrerClient = null
            }

            override fun onInstallReferrerServiceDisconnected() = Unit
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        LinkLab.getInstance(this).removeListener(this)
        runCatching { referrerClient?.endConnection() }
        referrerClient = null
    }

    companion object {
        private const val TAG = "LinklabSample"
    }
}
