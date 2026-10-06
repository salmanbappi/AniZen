package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

// ANZ -->
/**
 * Solves Cloudflare Turnstile challenges by finding the challenge bounds and
 * dispatching genuine hardware touch events through Android's input pipeline.
 *
 * This produces `event.isTrusted === true` inside the challenge iframe,
 * overcoming Turnstile's rejection of synthetic JavaScript clicks.
 */
object CloudflareSolver { // ANZ

    private const val CHECKBOX_OFFSET_X_CSS = 33.0f
    private const val MAX_IFRAME_FIND_ATTEMPTS = 30
    private const val MAX_SOLVE_ATTEMPTS = 3
    private const val IFRAME_FIND_DELAY = 1000L
    private const val POST_CLICK_DELAY = 2000L
    private const val VERIFY_CHECK_DELAY = 1500L
    private const val MAX_VERIFY_CHECKS = 20
    private val isSolving = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun solve(webView: WebView): Boolean {
        if (!isSolving.compareAndSet(false, true)) {
            return false
        }
        return try {
            val hasChallenge = detectChallenge(webView)
            if (!hasChallenge) {
                return true
            }

            var iframeInfo: IframeInfo? = null
            for (attempt in 0 until MAX_IFRAME_FIND_ATTEMPTS) {
                iframeInfo = findIframe(webView)
                if (iframeInfo != null && iframeInfo.width > 5 && iframeInfo.height > 5) {
                    break
                }
                iframeInfo = null
                if (attempt < MAX_IFRAME_FIND_ATTEMPTS - 1) {
                    delay(IFRAME_FIND_DELAY)
                }
            }

            if (iframeInfo == null) return false

            for (attempt in 0 until MAX_SOLVE_ATTEMPTS) {
                if (attempt > 0) {
                    delay(3000L)
                    val preState = getChallengeState(webView)
                    when (preState) {
                        CloudFlareState.SOLVED, CloudFlareState.GONE -> return true
                        CloudFlareState.VERIFYING -> {
                            val verifyResult = waitForVerification(webView)
                            if (verifyResult) return true
                        }
                        CloudFlareState.PRESENT -> {
                            // Challenge still present, retry click
                        }
                    }

                    iframeInfo = findIframe(webView)
                    if (iframeInfo == null) {
                        delay(POST_CLICK_DELAY)
                        iframeInfo = findIframe(webView)
                        if (iframeInfo == null) {
                            val state = getChallengeState(webView)
                            if (state == CloudFlareState.SOLVED || state == CloudFlareState.GONE) {
                                return true
                            }
                            continue
                        }
                    }
                }

                delay(POST_CLICK_DELAY)
                val currentIframe = iframeInfo ?: findIframe(webView) ?: continue
                dispatchTouchEvent(webView, currentIframe)
                delay(IFRAME_FIND_DELAY)
                val solved = waitForVerification(webView)
                if (solved) return true
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            isSolving.set(false)
        }
    }

    private suspend fun waitForVerification(webView: WebView): Boolean {
        var consecutiveVerifying = 0
        for (check in 0 until MAX_VERIFY_CHECKS) {
            delay(VERIFY_CHECK_DELAY)
            val state = getChallengeState(webView)
            when (state) {
                CloudFlareState.SOLVED -> {
                    delay(POST_CLICK_DELAY)
                    return true
                }
                CloudFlareState.GONE -> {
                    delay(IFRAME_FIND_DELAY)
                    return true
                }
                CloudFlareState.VERIFYING -> {
                    consecutiveVerifying++
                    if (consecutiveVerifying > 18) {
                        return false
                    }
                }
                CloudFlareState.PRESENT -> {
                    if (consecutiveVerifying > 0) return false
                    if (check > 3) return false
                }
            }
        }
        return false
    }

    private suspend fun detectChallenge(webView: WebView): Boolean {
        val js = """
            (function() {
                return !(!(
                    document.querySelector('script[src*="/cdn-cgi/challenge-platform/"]') ||
                    document.querySelector('input[name="cf-turnstile-response"]') ||
                    document.querySelector('script[src*="challenges.cloudflare.com/turnstile/v0"]') ||
                    document.querySelector('#challenge-stage') ||
                    document.querySelector('#turnstile-wrapper')
                ));
            })()
        """.trimIndent()
        val result = evaluateJs(webView, js)
        return result == "true"
    }

    private suspend fun getChallengeState(webView: WebView): CloudFlareState {
        val js = """
            (function() {
                var input = document.querySelector('input[name="cf-turnstile-response"]') ||
                            document.querySelector('input[id*="cf-chl-widget"]');
                if (input && input.value && input.value.length > 10) {
                    return 'solved';
                }

                var successText = document.getElementById('challenge-success-text');
                if (successText) {
                    var successParent = successText.closest('div[id]');
                    if (successParent && window.getComputedStyle(successParent).display !== 'none') {
                        return 'solved';
                    }
                }

                var loadingEl = document.querySelector('.loading-verifying');
                if (loadingEl) {
                    var style = window.getComputedStyle(loadingEl);
                    if (style.display !== 'none' && style.visibility !== 'hidden') {
                        return 'verifying';
                    }
                }

                var titleEl = document.querySelector('.ch-title');
                if (titleEl) {
                    var titleText = titleEl.textContent || '';
                    if (titleText.indexOf('Verifying') !== -1 ||
                        titleText.indexOf('verifying') !== -1) {
                        var captchaContainer = input ? input.closest('div[id]');
                        if (captchaContainer && window.getComputedStyle(captchaContainer).display === 'none') {
                            return 'verifying';
                        }
                    }
                }

                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    var src = iframes[i].src || '';
                    if (src.indexOf('challenges.cloudflare.com') !== -1 ||
                        src.indexOf('cdn-cgi/challenge-platform') !== -1) {
                        var rect = iframes[i].getBoundingClientRect();
                        if (rect.width > 0 && rect.height > 0) {
                            return 'present';
                        }
                    }
                }

                if (input) {
                    var container = input.closest('div[id]');
                    if (container) {
                        var cStyle = window.getComputedStyle(container);
                        if (cStyle.display !== 'none') {
                            var cRect = container.getBoundingClientRect();
                            if (cRect.width >= 50 && cRect.height >= 20) {
                                return 'present';
                            }
                        }
                    }
                }

                var hasScripts = !(!(
                    document.querySelector('script[src*="/cdn-cgi/challenge-platform/"]') ||
                    document.querySelector('script[src*="challenges.cloudflare.com/turnstile/v0"]')
                ));
                if (hasScripts) {
                    var rings = document.querySelector('.lds-ring');
                    if (rings) {
                        var ringParent = rings.closest('div[id]');
                        if (ringParent && window.getComputedStyle(ringParent).display !== 'none') {
                            return 'verifying';
                        }
                    }
                    return 'verifying';
                }

                return 'gone';
            })()
        """.trimIndent()

        val result = evaluateJs(webView, js)?.trim('"') ?: "gone"
        return when (result) {
            "solved" -> CloudFlareState.SOLVED
            "verifying" -> CloudFlareState.VERIFYING
            "present" -> CloudFlareState.PRESENT
            "gone" -> CloudFlareState.GONE
            else -> CloudFlareState.PRESENT
        }
    }

    private suspend fun findIframe(webView: WebView): IframeInfo? {
        val js = """
            (function() {
                var allIframes = [];
                function collectIframes(root) {
                    if (!root) return;
                    try {
                        var iframes = root.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            var iframe = iframes[i];
                            var rect = iframe.getBoundingClientRect();
                            allIframes.push({
                                src: iframe.src || '',
                                id: iframe.id || '',
                                name: iframe.name || '',
                                left: rect.left,
                                top: rect.top,
                                width: rect.width,
                                height: rect.height
                            });
                        }
                    } catch (e) {}
                    try {
                        var elements = root.querySelectorAll('*');
                        for (var j = 0; j < elements.length; j++) {
                            if (elements[j].shadowRoot) {
                                collectIframes(elements[j].shadowRoot);
                            }
                        }
                    } catch (e) {}
                }
                collectIframes(document);
                var found = null;
                for (var i = 0; i < allIframes.length; i++) {
                    var info = allIframes[i];
                    if (info.src.indexOf('challenges.cloudflare.com') !== -1 ||
                        info.src.indexOf('cdn-cgi/challenge-platform') !== -1) {
                        if (info.width > 0 && info.height > 0) {
                            found = info;
                            break;
                        }
                    }
                }

                if (!found) {
                    var input = document.querySelector('input[name="cf-turnstile-response"]') ||
                                document.querySelector('input[id*="cf-chl-widget"]');
                    if (input) {
                        var current = input;
                        while (current && current !== document.body) {
                            var rect = current.getBoundingClientRect();
                            if (rect.width >= 100 && rect.height >= 30) {
                                found = {
                                    src: 'fallback-turnstile-container',
                                    id: current.id || '',
                                    name: current.className || '',
                                    left: rect.left,
                                    top: rect.top,
                                    width: rect.width,
                                    height: rect.height
                                };
                                break;
                            }
                            current = current.parentElement;
                        }
                        if (!found && input.parentElement) {
                            var rect = input.parentElement.getBoundingClientRect();
                            found = {
                                src: 'fallback-turnstile-parent',
                                id: input.parentElement.id || '',
                                name: input.parentElement.className || '',
                                left: rect.left,
                                top: rect.top,
                                width: rect.width || 300,
                                height: rect.height || 65
                            };
                        }
                    }
                }

                return {
                    iframes: allIframes,
                    found: found
                };
            })()
        """.trimIndent()

        val result = evaluateJs(webView, js) ?: return null
        if (result == "null" || result.isBlank()) return null

        return try {
            val json = JSONObject(result)
            if (json.isNull("found")) {
                null
            } else {
                val foundObj = json.getJSONObject("found")
                IframeInfo(
                    left = foundObj.getDouble("left").toFloat(),
                    top = foundObj.getDouble("top").toFloat(),
                    width = foundObj.getDouble("width").toFloat(),
                    height = foundObj.getDouble("height").toFloat(),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun dispatchTouchEvent(webView: WebView, iframe: IframeInfo) {
        withContext(Dispatchers.Main) {
            val scale = webView.scale
            val cssX = iframe.left + CHECKBOX_OFFSET_X_CSS
            val cssY = iframe.top + if (iframe.height > 80f) 32.5f else (iframe.height / 2f)
            val viewX = cssX * scale
            val viewY = cssY * scale
            val downTime = SystemClock.uptimeMillis()

            val properties = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )

            val coordsDown = arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = viewX
                    y = viewY
                    pressure = 1.0f
                    size = 1.0f
                },
            )

            val downEvent = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                1,
                properties,
                coordsDown,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
            webView.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            delay(100L)

            val upTime = SystemClock.uptimeMillis()
            val coordsUp = arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = viewX
                    y = viewY
                    pressure = 0.0f
                    size = 1.0f
                },
            )

            val upEvent = MotionEvent.obtain(
                downTime,
                upTime,
                MotionEvent.ACTION_UP,
                1,
                properties,
                coordsUp,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
            webView.dispatchTouchEvent(upEvent)
            upEvent.recycle()
        }
    }

    private suspend fun evaluateJs(webView: WebView, js: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(js) { result ->
                    cont.resume(result)
                }
            }
        }
    }
}

data class IframeInfo(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

enum class CloudFlareState {
    SOLVED,
    VERIFYING,
    PRESENT,
    GONE,
}
// ANZ <--
