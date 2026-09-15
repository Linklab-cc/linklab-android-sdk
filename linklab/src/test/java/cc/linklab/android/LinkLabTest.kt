package cc.linklab.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Listener that records deliveries. */
private class RecordingListener : LinkLab.LinkLabListener {
    val received = CopyOnWriteArrayList<LinkLab.LinkData>()
    val fullLinks = CopyOnWriteArrayList<Uri>()

    override fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkLab.LinkData) {
        fullLinks += fullLink
        received += data
    }
}

/**
 * Pumps the Robolectric main looper (advancing its clock so postDelayed retries fire) until
 * [condition] holds or [timeoutMs] real milliseconds pass.
 */
private fun pumpUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

private fun pumpFor(ms: Long) {
    val deadline = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(10)
    }
}

private fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

private fun linkJson(
    id: String = "abc123",
    fullLink: String = "https://example.com/product/42?ref=email",
    extra: String = "",
): String = """{"id":"$id","fullLink":"$fullLink","createdAt":"2024-01-15T10:30:00Z",
    "updatedAt":"2024-01-16T10:30:00.250Z","userId":"secret","packageName":"cc.linklab.sample",
    "domain":"linklab.cc","domainType":"default" $extra}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostMatchingTest {
    private lateinit var sdk: LinkLab

    @Before
    fun setUp() {
        LinkLab.resetInstanceForTesting()
        sdk = LinkLab.getInstance(ApplicationProvider.getApplicationContext<Context>())
            .init(LinkLabConfig(customDomains = listOf("Links.Example.com"), installReferrerEnabled = false))
    }

    @Test
    fun linklabHostsAreRecognised() {
        assertTrue(sdk.isLinkLabLink(Uri.parse("https://linklab.cc/abc")))
        assertTrue(sdk.isLinkLabLink(Uri.parse("https://LinkLab.CC/abc")))
        assertTrue(sdk.isLinkLabLink(Uri.parse("https://sub.linklab.cc/abc")))
        assertTrue(sdk.isLinkLabLink(Uri.parse("http://demo.linklab.cc/")))
        assertTrue(sdk.isLinkLabLink(viewIntent("https://linklab.cc/abc")))
    }

    @Test
    fun customDomainIsCaseInsensitiveAndExact() {
        assertTrue(sdk.isLinkLabLink(Uri.parse("https://links.example.com/abc")))
        assertTrue(sdk.isLinkLabLink(Uri.parse("https://LINKS.EXAMPLE.COM/abc")))
        assertFalse("subdomains of custom domains are not implied", sdk.isLinkLabLink(Uri.parse("https://sub.links.example.com/abc")))
    }

    @Test
    fun foreignHostsAndSchemesAreRejected() {
        assertFalse(sdk.isLinkLabLink(Uri.parse("https://example.com/abc")))
        assertFalse(sdk.isLinkLabLink(Uri.parse("https://notlinklab.cc/abc")))
        assertFalse(sdk.isLinkLabLink(Uri.parse("https://linklab.cc.evil.com/abc")))
        assertFalse(sdk.isLinkLabLink(Uri.parse("myapp://linklab.cc/abc")))
        assertFalse(sdk.isLinkLabLink(Uri.parse("mailto:hello@linklab.cc")))
        assertFalse(sdk.isLinkLabLink(null as Uri?))
        assertFalse(sdk.isLinkLabLink(Intent(Intent.ACTION_VIEW)))
        assertFalse(sdk.isLinkLabLink(null as Intent?))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkDataTest {

    @Test
    fun fromJsonMergesAndDecodesParameters() {
        val json = org.json.JSONObject(
            """{"id":"x","fullLink":"https://example.com/p?a=1&b=hello%20world&c=keep",
                "parameters":{"b":"server","d":"4","n":5,"nul":null}}"""
        )
        val data = LinkLab.LinkData.fromJson(json, "https://linklab.cc/x")
        assertEquals("1", data.parameters["a"])
        assertEquals("server", data.parameters["b"])
        assertEquals("keep", data.parameters["c"])
        assertEquals("4", data.parameters["d"])
        assertEquals("5", data.parameters["n"])
        assertFalse(data.parameters.containsKey("nul"))
        assertEquals("https://linklab.cc/x", data.shortLink)
        assertEquals(LinkLab.LinkData.STATUS_RESOLVED, data.resolutionStatus)
        assertEquals(LinkLab.LinkData.MATCH_DIRECT, data.matchType)
        assertFalse(data.isDeferred)
        @Suppress("DEPRECATION")
        assertEquals(data.fullLink, data.rawLink)
    }

    @Test
    fun domainTypeMapping() {
        fun type(domainTypeJson: String, domain: String?) = LinkLab.LinkData.fromJson(
            org.json.JSONObject("""{"fullLink":"https://e.com/" $domainTypeJson ${domain?.let { ""","domain":"$it"""" } ?: ""}}"""),
            null,
        ).domainType

        assertEquals("linklab", type(""","domainType":"default"""", "linklab.cc"))
        assertEquals("custom", type(""","domainType":"custom"""", "links.example.com"))
        assertEquals("linklab", type("", "linklab.cc"))
        assertEquals("linklab", type("", "go.linklab.cc"))
        assertEquals("custom", type("", "links.example.com"))
    }

    @Test
    fun datesWithAndWithoutMillisAndOffsets() {
        assertEquals(1705314600000L, LinkLab.parseIso8601("2024-01-15T10:30:00Z"))
        assertEquals(1705314600250L, LinkLab.parseIso8601("2024-01-15T10:30:00.250Z"))
        assertEquals(1705314600123L, LinkLab.parseIso8601("2024-01-15T10:30:00.123456Z"))
        assertEquals(1705314600000L - 2 * 3_600_000L, LinkLab.parseIso8601("2024-01-15T10:30:00+02:00"))
        assertEquals(1705314600000L, LinkLab.parseIso8601("2024-01-15T10:30:00"))
        assertNull(LinkLab.parseIso8601("not a date"))
        assertNull(LinkLab.parseIso8601(null))

        val data = LinkLab.LinkData.fromJson(org.json.JSONObject(linkJson()), null)
        assertEquals(1705314600000L, data.createdAt)
        assertEquals(1705401000250L, data.updatedAt)
    }

    @Test
    fun missingOptionalFieldsBecomeNullAndEmpty() {
        val data = LinkLab.LinkData.fromJson(org.json.JSONObject("""{"fullLink":"https://e.com/x","id":null}"""), null)
        assertNull(data.id)
        assertNull(data.createdAt)
        assertNull(data.updatedAt)
        assertNull(data.packageName)
        assertNull(data.bundleId)
        assertNull(data.appStoreId)
        assertNull(data.domain)
        assertNull(data.shortLink)
        assertTrue(data.parameters.isEmpty())
        assertNull(data.errorMessage)
    }

    @Test
    fun unrecognizedAndFailedKeepOriginalUrlAndQuery() {
        val uri = Uri.parse("https://linklab.cc/?utm_source=x&name=John%20Doe")
        val unrec = LinkLab.LinkData.unrecognized(uri)
        assertEquals(uri.toString(), unrec.fullLink)
        assertEquals(uri.toString(), unrec.shortLink)
        assertEquals("linklab.cc", unrec.domain)
        assertEquals("unrecognized", unrec.domainType)
        assertEquals("unrecognized", unrec.resolutionStatus)
        assertEquals(mapOf("utm_source" to "x", "name" to "John Doe"), unrec.parameters)
        assertNull(unrec.id)

        val failed = LinkLab.LinkData.failed(uri, "boom")
        assertEquals("failed", failed.resolutionStatus)
        assertEquals("boom", failed.errorMessage)
        assertEquals("direct", failed.matchType)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessDynamicLinkTest {
    private lateinit var server: MockWebServer
    private lateinit var sdk: LinkLab
    private lateinit var listener: RecordingListener

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        LinkLab.resetInstanceForTesting()
        listener = RecordingListener()
        sdk = newSdk()
    }

    private fun newSdk(timeout: Double = 2.0, retries: Int = 3): LinkLab {
        LinkLab.resetInstanceForTesting()
        return LinkLab.getInstance(ApplicationProvider.getApplicationContext<Context>())
            .init(
                LinkLabConfig(
                    customDomains = listOf("links.example.com"),
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    networkTimeout = timeout,
                    networkRetryCount = retries,
                    installReferrerEnabled = false,
                )
            )
            .addListener(listener)
    }

    @After
    fun tearDown() {
        server.shutdown()
        LinkLab.resetInstanceForTesting()
    }

    @Test
    fun foreignIntentReturnsFalseAndDeliversNothing() {
        assertFalse(sdk.processDynamicLink(viewIntent("https://example.com/abc")))
        assertFalse(sdk.processDynamicLink(viewIntent("myapp://linklab.cc/abc")))
        assertFalse(sdk.processDynamicLink(Intent(Intent.ACTION_MAIN)))
        assertFalse(sdk.processDynamicLink(null))
        pumpFor(300)
        assertTrue(listener.received.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rootPathDeliversUnrecognizedWithoutNetwork() {
        assertTrue(sdk.processDynamicLink(viewIntent("https://links.example.com/?campaign=spring&x=a%20b")))
        assertTrue(pumpUntil { listener.received.size == 1 })
        val data = listener.received.single()
        assertEquals("unrecognized", data.resolutionStatus)
        assertEquals("https://links.example.com/?campaign=spring&x=a%20b", data.fullLink)
        assertEquals(mapOf("campaign" to "spring", "x" to "a b"), data.parameters)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun successfulResolution() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(linkJson()))
        assertTrue(sdk.processDynamicLink(viewIntent("https://linklab.cc/abc123?src=qr")))
        assertTrue(pumpUntil { listener.received.size == 1 })

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/links/abc123?domain=linklab.cc", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("android/${LinkLab.VERSION}", request.getHeader("X-Linklab-Sdk"))
        val appHeader = request.getHeader("X-Linklab-App")
        assertNotNull(appHeader)
        assertEquals(ApplicationProvider.getApplicationContext<Context>().packageName, appHeader)
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Linklab-Android-SDK/${LinkLab.VERSION} ("))

        val data = listener.received.single()
        assertEquals("resolved", data.resolutionStatus)
        assertEquals("abc123", data.id)
        assertEquals("https://example.com/product/42?ref=email", data.fullLink)
        assertEquals(Uri.parse(data.fullLink), listener.fullLinks.single())
        assertEquals("https://linklab.cc/abc123?src=qr", data.shortLink)
        assertEquals("linklab", data.domainType)
        assertEquals("cc.linklab.sample", data.packageName)
        assertEquals(mapOf("ref" to "email"), data.parameters)
        assertEquals("direct", data.matchType)
        assertFalse(data.isDeferred)
        assertNull(data.errorMessage)
    }

    @Test
    fun notFoundDeliversUnrecognized() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Link not found"}"""))
        assertTrue(sdk.processDynamicLink(viewIntent("https://linklab.cc/missing?a=1")))
        assertTrue(pumpUntil { listener.received.size == 1 })
        val data = listener.received.single()
        assertEquals("unrecognized", data.resolutionStatus)
        assertEquals("https://linklab.cc/missing?a=1", data.fullLink)
        assertEquals(mapOf("a" to "1"), data.parameters)
        assertNull(data.id)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverErrorIsRetriedThenResolved() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody(linkJson()))
        assertTrue(sdk.processDynamicLink(viewIntent("https://linklab.cc/abc123")))
        assertTrue(pumpUntil { listener.received.size == 1 })
        assertEquals("resolved", listener.received.single().resolutionStatus)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun clientErrorIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(400))
        assertTrue(sdk.processDynamicLink(viewIntent("https://linklab.cc/abc123")))
        assertTrue(pumpUntil { listener.received.size == 1 })
        val data = listener.received.single()
        assertEquals("failed", data.resolutionStatus)
        assertEquals("HTTP 400", data.errorMessage)
        pumpFor(200)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun timeoutDeliversFailed() {
        sdk = newSdk(timeout = 0.3, retries = 1)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(sdk.processDynamicLink(viewIntent("https://linklab.cc/slow?x=1")))
        assertTrue(pumpUntil(15_000) { listener.received.size == 1 })
        val data = listener.received.single()
        assertEquals("failed", data.resolutionStatus)
        assertNotNull(data.errorMessage)
        assertEquals("https://linklab.cc/slow?x=1", data.fullLink)
        assertEquals(mapOf("x" to "1"), data.parameters)
        assertEquals("direct", data.matchType)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun duplicateUrlWhileInFlightIsIgnoredButReprocessedAfterCompletion() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(linkJson()).setBodyDelay(400, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setResponseCode(200).setBody(linkJson()))
        val intent = viewIntent("https://linklab.cc/abc123")
        assertTrue(sdk.processDynamicLink(intent))
        assertTrue(sdk.processDynamicLink(intent))
        assertTrue(pumpUntil { listener.received.size == 1 })
        pumpFor(300)
        assertEquals(1, listener.received.size)
        assertEquals(1, server.requestCount)

        assertTrue(sdk.processDynamicLink(intent))
        assertTrue(pumpUntil { listener.received.size == 2 })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun lateListenerReceivesLastLinkExactlyOnce() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(linkJson()))
        assertTrue(sdk.processDynamicLink(viewIntent("https://linklab.cc/abc123")))
        assertTrue(pumpUntil { listener.received.size == 1 })

        val late = RecordingListener()
        sdk.addListener(late)
        assertTrue(pumpUntil { late.received.size == 1 })
        assertEquals(listener.received.single(), late.received.single())

        sdk.addListener(late) // adding the same listener again must not replay again
        pumpFor(200)
        assertEquals(1, late.received.size)
        assertEquals(1, listener.received.size) // the original listener is not re-delivered

        val fresh = RecordingListener()
        sdk.addListener(fresh)
        pumpFor(200)
        assertEquals(1, fresh.received.size)
    }

    @Test
    fun throwingListenerDoesNotStarveOthers() {
        val bad = object : LinkLab.LinkLabListener {
            override fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkLab.LinkData) = throw IllegalStateException("boom")
        }
        val good = RecordingListener()
        LinkLab.resetInstanceForTesting()
        sdk = LinkLab.getInstance(ApplicationProvider.getApplicationContext<Context>())
            .init(LinkLabConfig(baseUrl = server.url("/").toString(), installReferrerEnabled = false))
            .addListener(bad)
            .addListener(good)
        server.enqueue(MockResponse().setResponseCode(200).setBody(linkJson()))
        assertTrue(sdk.processDynamicLink(viewIntent("https://linklab.cc/abc123")))
        assertTrue(pumpUntil { good.received.size == 1 })
    }

    @Test
    fun getDynamicLinkRejectsForeignUri() {
        assertFalse(sdk.getDynamicLink(Uri.parse("https://example.com/abc")))
        assertFalse(sdk.getDynamicLink(null))
        server.enqueue(MockResponse().setResponseCode(200).setBody(linkJson()))
        assertTrue(sdk.getDynamicLink(Uri.parse("https://linklab.cc/abc123")))
        assertTrue(pumpUntil { listener.received.size == 1 })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallReferrerParsingTest {
    private lateinit var sdk: LinkLab

    @Before
    fun setUp() {
        LinkLab.resetInstanceForTesting()
        sdk = LinkLab.getInstance(ApplicationProvider.getApplicationContext<Context>())
            .init(LinkLabConfig(installReferrerEnabled = false))
    }

    @Test
    fun plainReferrer() {
        val info = sdk.parseReferrer("linklab_id=abc123&domain=Links.Example.com")!!
        assertEquals("abc123", info.linkId)
        assertEquals("links.example.com", info.domain)
    }

    @Test
    fun plainReferrerWithUrlEncodedValuesAndOtherParams() {
        val info = sdk.parseReferrer("utm_source=google&linklab_id=id%2Fwith%20space&domain=demo.linklab.cc&utm_medium=cpc")!!
        assertEquals("id/with space", info.linkId)
        assertEquals("demo.linklab.cc", info.domain)
    }

    @Test
    fun base64Referrer() {
        val encoded = android.util.Base64.encodeToString(
            "linklab_id=xyz789&domain=linklab.cc".toByteArray(), android.util.Base64.NO_WRAP
        )
        val info = sdk.parseReferrer(encoded)!!
        assertEquals("xyz789", info.linkId)
        assertEquals("linklab.cc", info.domain)
    }

    @Test
    fun missingDomainDefaultsToLinklab() {
        assertEquals("linklab.cc", sdk.parseReferrer("linklab_id=abc")!!.domain)
    }

    @Test
    fun organicAndGarbageReferrersYieldNull() {
        assertNull(sdk.parseReferrer("utm_source=google-play&utm_medium=organic"))
        assertNull(sdk.parseReferrer(""))
        assertNull(sdk.parseReferrer(null))
        assertNull(sdk.parseReferrer("not base64 and no params"))
        assertNull(sdk.parseReferrer(android.util.Base64.encodeToString("utm_source=organic".toByteArray(), android.util.Base64.NO_WRAP)))
    }
}
