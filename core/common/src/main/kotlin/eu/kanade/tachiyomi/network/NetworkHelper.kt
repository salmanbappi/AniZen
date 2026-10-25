package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.IgnoreGzipInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.brotli.BrotliInterceptor
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/* SY --> */
open /* SY <-- */ class NetworkHelper(
    private val context: Context,
    private val preferences: NetworkPreferences,
    // SY -->
    val isDebugBuild: Boolean,
    // SY <--
) {

    /* SY --> */
    open /* SY <-- */val cookieJar = AndroidCookieJar()

    /* SY --> */
    open /* SY <-- */val client: OkHttpClient =
        // KMK -->
        clientWithTimeOut()

    /**
     * Specialized client for high-performance downloading.
     * Mimics 1DM+/ADM by allowing many concurrent connections to the same host.
     */
    val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 512 // Increased from 256
                    maxRequestsPerHost = 64
                },
            )
            .connectionPool(ConnectionPool(256, 5, TimeUnit.MINUTES)) // More aggressive pooling
            // A download call must survive however long the transfer takes. The 120s
            // callTimeout inherited from the base client kills large downloads mid-stream
            // and forces the retry loop to resume from the last byte.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Client for the embedded torrent server. TorrServer only starts producing a response
     * once the torrent swarm connects, so header waits and mid-stream stalls can legitimately
     * take minutes. Reads are given 10 minutes and there is no overall call deadline.
     */
    val torrentClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Timeout in unit of seconds.
     */
    fun clientWithTimeOut(
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
        // KMK <--
    ): OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .fastFallback(true)
            .retryOnConnectionFailure(true)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 128
                    maxRequestsPerHost = 32
                },
            )
            .connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
            .cookieJar(cookieJar)
            // KMK -->
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .callTimeout(callTimeout, TimeUnit.SECONDS)
            // KMK <--
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            .addNetworkInterceptor(IgnoreGzipInterceptor())
            .addNetworkInterceptor(BrotliInterceptor)

        if (isDebugBuild) {
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        builder.addInterceptor(
            CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider),
        )

        // ANZ -->
        // Pin User-Agent on requests carrying cf_clearance at the wire level
        builder.addNetworkInterceptor { chain ->
            val original = chain.request()
            val cookieHeader = original.header("Cookie").orEmpty()
            val pinnedUa = solveUaFor(original.url.host)
            val req = if (
                pinnedUa != null &&
                cookieHeader.contains("cf_clearance") &&
                original.header("User-Agent") != pinnedUa
            ) {
                original.newBuilder().header("User-Agent", pinnedUa).build()
            } else {
                original
            }
            chain.proceed(req)
        }
        // ANZ <--

        when (preferences.dohProvider().get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            PREF_DOH_LIBREDNS -> builder.dohLibreDNS()
        }

        builder.build()
    }

    // KMK <--

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default", ReplaceWith("client"))
    @Suppress("UNUSED")
    /* SY --> */
    open /* SY <-- */val cloudflareClient: OkHttpClient
        get() = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent().get().trim()

    /**
     * Simple download function for general use (e.g. app updates).
     * Not multi-threaded or resumable, but handles progress via listener.
     */
    fun downloadFile(url: String, outputFile: File, progressListener: ProgressListener) {
        val request = Request.Builder().url(url).build()
        client.newCachelessCallWithProgress(request, progressListener).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected response code: ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")
            outputFile.outputStream().use { output ->
                body.byteStream().copyTo(output)
            }
        }
    }

    companion object {
        // ANZ -->
        /**
         * Timestamp (ms) when a Cloudflare challenge was last successfully solved
         * (either headless or manual in WebView).
         */
        @Volatile
        var lastSolveAtMs: Long = 0L // ANZ

        /**
         * Map of host -> User-Agent that solved Cloudflare for that host.
         * Prevents UA mismatch between requests that earned cf_clearance and requests that send it.
         */
        private val solveUaByHost = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun rememberSolveUa(host: String, ua: String?) {
            if (!ua.isNullOrBlank()) {
                solveUaByHost[host] = ua
            }
        }

        fun solveUaFor(host: String): String? = solveUaByHost[host]
        // ANZ <--
    }
}
