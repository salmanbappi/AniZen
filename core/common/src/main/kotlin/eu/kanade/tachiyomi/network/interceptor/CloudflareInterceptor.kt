package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // ANZ -->
        val isChallenge = response.header("cf-mitigated") == "challenge"
        val isCloudflare = response.header("Server") in SERVER_CHECK
        val isErrorCode = response.code in ERROR_CODES
        return (isChallenge && (isCloudflare || response.header("Server") == null)) ||
            (isErrorCode && isCloudflare)
        // ANZ <--
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        // ANZ -->
        val solvedAt = NetworkHelper.lastSolveAtMs
        val justSolved = solvedAt > 0 && (System.currentTimeMillis() - solvedAt) < SOLVE_GRACE_MS
        if (justSolved && cookieManager.get(request.url).any { it.name == "cf_clearance" }) {
            response.close()
            val retried = chain.proceed(request)
            if (!shouldIntercept(retried)) {
                return retried
            }
            retried.close()
        }
        // ANZ <--

        try {
            response.close()
            // ANZ -->
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            android.webkit.CookieManager.getInstance().flush()
            // ANZ <--

            val originalUserAgent = request.header("User-Agent") ?: run {
                try {
                    android.webkit.WebSettings.getDefaultUserAgent(context)
                } catch (e: Exception) {
                    defaultUserAgentProvider()
                }
            }
            val cleanUserAgent = cleanUserAgent(originalUserAgent)

            val newRequest = request.newBuilder()
                .header("User-Agent", cleanUserAgent)
                .apply {
                    addClientHints(this, cleanUserAgent)
                }
                .build()

            resolveWithWebView(newRequest, oldCookie, cleanUserAgent)

            return chain.proceed(newRequest)
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun cleanUserAgent(userAgent: String): String {
        // ANZ -->
        return userAgent
            .replace(ANIYOMI_USER_AGENT_REGEX, "")
            .replace(ANIZEN_USER_AGENT_REGEX, "")
            .replace(TACHIYOMI_USER_AGENT_REGEX, "")
            .replace("; wv", "")
            .trim()
        // ANZ <--
    }

    private fun addClientHints(builder: Request.Builder, userAgent: String) {
        val chromeVersionMatch = CHROME_VERSION_REGEX.find(userAgent)
        val chromeVersion = chromeVersionMatch?.groupValues?.get(1) ?: "131"

        val androidVersion = android.os.Build.VERSION.RELEASE.takeWhile { it.isDigit() }.ifEmpty { "13" }

        builder.header("Sec-CH-UA", "\"Chromium\";v=\"$chromeVersion\", \"Google Chrome\";v=\"$chromeVersion\", \"Not=A?Brand\";v=\"99\"")
        builder.header("Sec-CH-UA-Mobile", "?1")
        builder.header("Sec-CH-UA-Platform", "\"Android\"")
        builder.header("Sec-CH-UA-Platform-Version", "\"$androidVersion.0.0\"")
    }

    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?, cleanUserAgent: String) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null
        var attachedToWindow = false
        var parentView: android.view.ViewGroup? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            val activity = ActivityTracker.activeActivity?.get()
            val webViewContext = activity ?: context
            val createdWebView = createWebView(originalRequest, webViewContext).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1080, 1920)
                measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 1080, 1920)

                // Render visible to pass Cloudflare Turnstile anti-clickjacking opacity checks
                alpha = 1.0f

                requestFocus()
                onResume()
                resumeTimers()
            }
            webview = createdWebView

            // ANZ -->
            createdWebView.addJavascriptInterface(
                object {
                    @android.webkit.JavascriptInterface
                    fun interactiveBegin() {
                        challengeFound = true
                    }
                },
                "anizen",
            )
            // ANZ <--

            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    parentView = activity.findViewById(android.R.id.content) as? android.view.ViewGroup
                    parentView?.addView(createdWebView, 0, android.view.ViewGroup.LayoutParams(1080, 1920))
                    attachedToWindow = true
                } catch (e: Exception) {
                    // Fallback to detached view
                }
            }

            createdWebView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript(
                        """
                        try {
                            if ('webdriver' in navigator && navigator.webdriver) {
                                Object.defineProperty(navigator, 'webdriver', { get: () => false });
                            }
                            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
                            Object.defineProperty(navigator, 'plugins', { get: () => [
                                { description: "Portable Document Format", filename: "internal-pdf-viewer", name: "Chromium PDF Viewer" }
                            ] });
                            window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {} };

                            // Override document focus & visibility
                            Object.defineProperty(document, 'hidden', { get: () => false });
                            Object.defineProperty(document, 'visibilityState', { get: () => 'visible' });
                            Object.defineProperty(document, 'hasFocus', { get: () => () => true });
                            window.hasFocus = () => true;

                            // Listen for Cloudflare interactive challenges
                            window.addEventListener('message', function(e) {
                                if (e.data && (e.data === 'interactiveBegin' || (typeof e.data === 'object' && e.data.event === 'interactiveBegin'))) {
                                    if (window.anizen && window.anizen.interactiveBegin) {
                                        window.anizen.interactiveBegin();
                                    }
                                }
                            });
                        } catch (e) {}
                        """.trimIndent(),
                        null
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    // ANZ -->
                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        NetworkHelper.lastSolveAtMs = System.currentTimeMillis()
                        NetworkHelper.rememberSolveUa(origRequestUrl.toHttpUrl().host, cleanUserAgent)
                        latch.countDown()
                        return
                    }

                    val title = (view.title ?: "").lowercase()
                    if (title.contains("just a moment") ||
                        title.contains("attention required") ||
                        title.contains("un instant") ||
                        title.contains("einen moment") ||
                        title.contains("un momento") ||
                        title.contains("один момент")
                    ) {
                        challengeFound = true
                    }

                    if (url == origRequestUrl && !challengeFound) {
                        // The first request didn't return the challenge, abort.
                        latch.countDown()
                        return
                    }

                    // Dispatch native touch events through Android pipeline to bypass Turnstile
                    CoroutineScope(Dispatchers.Default).launch {
                        if (CloudflareSolver.solve(view)) {
                            for (i in 0 until 10) {
                                if (isCloudFlareBypassed()) {
                                    cloudflareBypassed = true
                                    NetworkHelper.lastSolveAtMs = System.currentTimeMillis()
                                    NetworkHelper.rememberSolveUa(origRequestUrl.toHttpUrl().host, cleanUserAgent)
                                    latch.countDown()
                                    return@launch
                                }
                                delay(500)
                            }
                        }
                    }
                    // ANZ <--
                }

                // ANZ -->
                override fun onReceivedHttpError( // ANZ
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.statusCode in ERROR_CODES) {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        // Network error occurred on main frame, unlock thread.
                        latch.countDown()
                    }
                }
                // ANZ <--
            }

            webview?.loadUrl(origRequestUrl, headers)
        }

        val pollTimer = java.util.Timer()
        pollTimer.schedule(object : java.util.TimerTask() {
            override fun run() {
                val currentCookie = cookieManager.get(origRequestUrl.toHttpUrl())
                    .firstOrNull { it.name == "cf_clearance" }
                if (currentCookie != null && currentCookie != oldCookie) {
                    cloudflareBypassed = true
                    // ANZ -->
                    NetworkHelper.lastSolveAtMs = System.currentTimeMillis()
                    NetworkHelper.rememberSolveUa(origRequestUrl.toHttpUrl().host, cleanUserAgent)
                    // ANZ <--
                    latch.countDown()
                    pollTimer.cancel()
                    return
                }

                executor.execute {
                    webview?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var href = (document.location && document.location.href) || '';
                                if (href === '' || href === 'about:blank') return 'wait';
                                if (document.readyState !== 'interactive' && document.readyState !== 'complete') return 'wait';
                                var t = (document.title || '').toLowerCase();
                                if (t.indexOf('attention required') !== -1 || t.indexOf('access denied') !== -1) return 'error';
                                if (t.indexOf('just a moment') !== -1 || t.indexOf('un instant') !== -1 ||
                                    t.indexOf('einen moment') !== -1 || t.indexOf('un momento') !== -1 ||
                                    t.indexOf('один момент') !== -1) return 'wait';
                                if (document.querySelector('#challenge-running, #challenge-stage, #cf-challenge-running, .cf-browser-verification, #turnstile-wrapper, #cf-please-wait, script[src*="challenge-platform"]')) return 'wait';
                                if (!document.body || document.body.children.length === 0) return 'wait';
                                return 'ok';
                            } catch (e) { return 'wait'; }
                        })()
                        """.trimIndent()
                    ) { state ->
                        if (state == "\"ok\"") {
                            val current = cookieManager.get(origRequestUrl.toHttpUrl())
                                .firstOrNull { it.name == "cf_clearance" }
                            if (current != null && current != oldCookie) {
                                cloudflareBypassed = true
                                // ANZ -->
                                NetworkHelper.lastSolveAtMs = System.currentTimeMillis()
                                NetworkHelper.rememberSolveUa(origRequestUrl.toHttpUrl().host, cleanUserAgent)
                                // ANZ <--
                                latch.countDown()
                                pollTimer.cancel()
                            }
                        }
                    }
                }
            }
        }, 0L, 1000L)

        try {
            latch.awaitFor30Seconds()
        } finally {
            pollTimer.cancel()
        }

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                if (attachedToWindow) {
                    try {
                        parentView?.removeView(this)
                    } catch (e: Exception) {}
                }
                stopLoading()
                destroy()
            }
            android.webkit.CookieManager.getInstance().flush()
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed) {
            // Prompt user to update WebView if it seems too outdated
            if (isWebViewOutdated) {
                context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
            }

            throw CloudflareBypassException()
        }
    }
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")
// ANZ -->
private const val SOLVE_GRACE_MS = 90_000L // ANZ
// ANZ <--

// Hoisted out of the interceptor body: these previously recompiled on every request.
private val ANIYOMI_USER_AGENT_REGEX = Regex("\\s+Aniyomi/\\S+", RegexOption.IGNORE_CASE)
private val ANIZEN_USER_AGENT_REGEX = Regex("\\s+AniZen/\\S+", RegexOption.IGNORE_CASE)
private val TACHIYOMI_USER_AGENT_REGEX = Regex("\\s+Tachiyomi/\\S+", RegexOption.IGNORE_CASE)
private val CHROME_VERSION_REGEX = Regex("Chrome/(\\d+)")

private class CloudflareBypassException : Exception()

object ActivityTracker : android.app.Application.ActivityLifecycleCallbacks {
    var activeActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) {}
    override fun onActivityResumed(activity: android.app.Activity) {
        activeActivity = java.lang.ref.WeakReference(activity)
    }
    override fun onActivityPaused(activity: android.app.Activity) {
        if (activeActivity?.get() == activity) {
            activeActivity = null
        }
    }
    override fun onActivityStopped(activity: android.app.Activity) {}
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
}
