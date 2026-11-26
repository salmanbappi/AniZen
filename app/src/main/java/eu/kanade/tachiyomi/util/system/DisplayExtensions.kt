package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.TabletUiMode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TABLET_UI_REQUIRED_SCREEN_WIDTH_DP = 720

// some tablets have screen width like 711dp = 1600px / 2.25
private const val TABLET_UI_MIN_SCREEN_WIDTH_PORTRAIT_DP = 700

// make sure icons on the nav rail fit
private const val TABLET_UI_MIN_SCREEN_WIDTH_LANDSCAPE_DP = 600

fun Context.isTv(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return true
    return isTvBox(this)
}

fun Configuration.isTabletUi(): Boolean {
    return smallestScreenWidthDp >= TABLET_UI_REQUIRED_SCREEN_WIDTH_DP
}

// TODO: move the logic to `isTabletUi()` when main activity is rewritten in Compose
fun Context.prepareTabletUiContext(): Context {
    val configuration = resources.configuration
    val expected = when (Injekt.get<UiPreferences>().tabletUiMode().get()) {
        TabletUiMode.AUTOMATIC ->
            configuration.smallestScreenWidthDp >= when (configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> TABLET_UI_MIN_SCREEN_WIDTH_PORTRAIT_DP
                else -> TABLET_UI_MIN_SCREEN_WIDTH_LANDSCAPE_DP
            }
        TabletUiMode.ALWAYS -> true
        TabletUiMode.LANDSCAPE -> configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        TabletUiMode.NEVER -> false
    }
    if (configuration.isTabletUi() != expected) {
        val overrideConf = Configuration()
        overrideConf.setTo(configuration)
        overrideConf.smallestScreenWidthDp = if (expected) {
            overrideConf.smallestScreenWidthDp.coerceAtLeast(TABLET_UI_REQUIRED_SCREEN_WIDTH_DP)
        } else {
            overrideConf.smallestScreenWidthDp.coerceAtMost(TABLET_UI_REQUIRED_SCREEN_WIDTH_DP - 1)
        }
        return createConfigurationContext(overrideConf)
    }
    return this
}

/**
 * Returns true if current context is in night mode
 */
fun Context.isNightMode(): Boolean {
    return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

/**
 * Checks whether if the device has a display cutout (i.e. notch, camera cutout, etc.).
 *
 * Only works in Android 9+.
 */
fun Activity.hasDisplayCutout(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        window.decorView.rootWindowInsets?.displayCutout != null
}

/**
 * Gets system's config_navBarNeedsScrim boolean flag added in Android 10, defaults to true.
 */
fun Context.isNavigationBarNeedsScrim(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        InternalResourceHelper.getBoolean(this, "config_navBarNeedsScrim", true)
}

// ANZ -->
private const val DEFAULT_REFRESH_RATE_HZ = 60f

private fun Context.resolveDisplay(): Display? {
    val activity = findActivity()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity?.let { runCatching { it.display }.getOrNull() }
            ?: runCatching { display }.getOrNull()
            ?: (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
    } else {
        val windowManager = activity?.windowManager ?: getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay
    }
}

private fun Display.sameResolutionModes(): List<Display.Mode> {
    val modes = supportedModes?.toList().orEmpty()
    if (modes.isEmpty()) return emptyList()
    val currentMode = mode
    val maxDim = maxOf(currentMode.physicalWidth, currentMode.physicalHeight)
    val minDim = minOf(currentMode.physicalWidth, currentMode.physicalHeight)
    return modes.filter {
        maxOf(it.physicalWidth, it.physicalHeight) == maxDim &&
            minOf(it.physicalWidth, it.physicalHeight) == minDim
    }.ifEmpty { modes }
}

private fun Activity.applyDisplayMode(mode: Display.Mode) {
    val currentWindow = window ?: return
    val params = currentWindow.attributes
    var updated = false
    if (params.preferredDisplayModeId != mode.modeId) {
        params.preferredDisplayModeId = mode.modeId
        updated = true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && params.preferredRefreshRate != mode.refreshRate) {
        params.preferredRefreshRate = mode.refreshRate
        updated = true
    }
    if (updated) {
        currentWindow.attributes = params
    }
}

/**
 * Returns distinct supported display refresh rates (rounded to nearest integer Hz, sorted ascending)
 * matching the current display resolution.
 */
fun Context.getSupportedRefreshRates(): List<Int> {
    return try {
        val modes = resolveDisplay()?.sameResolutionModes().orEmpty()
        modes.map { it.refreshRate.roundToInt() }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .ifEmpty { listOf(getDisplayRefreshRate().roundToInt()) }
    } catch (_: Exception) {
        listOf(DEFAULT_REFRESH_RATE_HZ.roundToInt())
    }
}

/**
 * Switches the window to the display mode matching [targetFps] (or the highest supported mode
 * when [targetFps] <= 0) and returns that mode's refresh rate in Hz.
 */
fun Activity.setTargetRefreshRate(targetFps: Int): Float {
    return try {
        val display = resolveDisplay() ?: return DEFAULT_REFRESH_RATE_HZ
        val sameResModes = display.sameResolutionModes()
        val chosenMode = if (targetFps <= 0) {
            sameResModes.maxByOrNull { it.refreshRate }
                ?: display.supportedModes?.maxByOrNull { it.refreshRate }
        } else {
            sameResModes.minByOrNull { abs(it.refreshRate - targetFps.toFloat()) }
                ?: display.supportedModes?.minByOrNull { abs(it.refreshRate - targetFps.toFloat()) }
        }
        if (chosenMode != null) {
            applyDisplayMode(chosenMode)
            chosenMode.refreshRate
        } else {
            getDisplayRefreshRate()
        }
    } catch (_: Exception) {
        getDisplayRefreshRate()
    }
}
// ANZ <--

/**
 * Enables the highest supported refresh rate mode for the window.
 * Ensures 90Hz / 120Hz / 144Hz displays run at peak fluidity immediately
 * upon launching the app, preventing the initial 60Hz scroll lag.
 */
fun Activity.enableHighRefreshRate() {
    // ANZ -->
    setTargetRefreshRate(0)
    // ANZ <--
}

/**
 * Returns the current display's refresh rate in Hz, or a sensible fallback (60Hz).
 */
fun Context.getDisplayRefreshRate(): Float {
    return try {
        // ANZ -->
        resolveDisplay()?.refreshRate?.takeIf { it > 0f } ?: DEFAULT_REFRESH_RATE_HZ
        // ANZ <--
    } catch (_: Exception) {
        DEFAULT_REFRESH_RATE_HZ // ANZ
    }
}

// ANZ -->
/**
 * Resets the window's preferred refresh rate and display mode back to system default.
 */
fun Activity.resetRefreshRate() {
    try {
        val window = window ?: return
        val params = window.attributes
        var updated = false
        if (params.preferredDisplayModeId != 0) {
            params.preferredDisplayModeId = 0
            updated = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && params.preferredRefreshRate != 0f) {
            params.preferredRefreshRate = 0f
            updated = true
        }
        if (updated) {
            window.attributes = params
        }
    } catch (_: Exception) {
    }
}
// ANZ <--
