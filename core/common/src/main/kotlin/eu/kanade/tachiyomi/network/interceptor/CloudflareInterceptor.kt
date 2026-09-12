package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
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
        // Check if Cloudflare anti-bot is on
        return response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            android.webkit.CookieManager.getInstance().flush()
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }

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

            resolveWithWebView(newRequest, oldCookie)

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
        return userAgent
            .replace(ANIYOMI_USER_AGENT_REGEX, "")
            .replace(ANIZEN_USER_AGENT_REGEX, "")
            .replace(TACHIYOMI_USER_AGENT_REGEX, "")
            .trim()
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

    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
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

                // Render invisible but active
                alpha = 0.01f
                setBackgroundColor(0)

                requestFocus()
                onResume()
                resumeTimers()
            }
            webview = createdWebView

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
                            Object.defineProperty(navigator, 'webdriver', { get: () => false });
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

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }

                    if (url == origRequestUrl && !challengeFound) {
                        // The first request didn't return the challenge, abort.
                        latch.countDown()
                    }

                    // Inject Turnstile auto-click script
                    view.evaluateJavascript(
                        """
                        (function() {
                            const MIN_DELAY = 1000;
                            const MAX_DELAY = 3000;
                            const CHECK_INTERVAL = 2000;

                            function getRandomDelay() {
                                return Math.floor(Math.random() * (MAX_DELAY - MIN_DELAY + 1)) + MIN_DELAY;
                            }

                            function findWidget(root) {
                                const widget = root.querySelector('#challenge-stage input[type="checkbox"]') ||
                                       root.querySelector('input[name="cf-turnstile-response"]') ||
                                       root.querySelector('.ctp-checkbox-container input') ||
                                       root.querySelector('.cf-turnstile-wrapper iframe') ||
                                       root.querySelector('#turnstile-wrapper iframe');

                                if (widget) return widget;

                                const all = root.querySelectorAll('*');
                                for (let i = 0; i < all.length; i++) {
                                    if (all[i].shadowRoot) {
                                        const found = findWidget(all[i].shadowRoot);
                                        if (found) return found;
                                    }
                                }
                                return null;
                            }

                            function attemptClick() {
                                const element = findWidget(document);
                                if (element) {
                                    setTimeout(() => {
                                        if (element.tagName === 'IFRAME') {
                                            element.focus();
                                        } else {
                                            element.focus();
                                            element.click();
                                            element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                            element.dispatchEvent(new Event('change', { bubbles: true }));
                                            element.dispatchEvent(new Event('input', { bubbles: true }));
                                        }
                                    }, getRandomDelay());
                                    return true;
                                }
                                return false;
                            }

                            const observer = new MutationObserver((mutations) => {
                                if (attemptClick()) {
                                    observer.disconnect();
                                }
                            });

                            observer.observe(document.body, { childList: true, subtree: true });

                            if (attemptClick()) {
                                observer.disconnect();
                            }

                            const interval = setInterval(() => {
                                if (attemptClick()) {
                                    clearInterval(interval);
                                    observer.disconnect();
                                }
                            }, CHECK_INTERVAL);
                        })();
                        """.trimIndent(),
                        null
                    )
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        if (error.errorCode in ERROR_CODES) {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }
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
                            if (current != null) {
                                cloudflareBypassed = true
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
