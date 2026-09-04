package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.TabletUiMode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

/**
 * Enables the highest supported refresh rate mode for the window.
 * Ensures 90Hz / 120Hz / 144Hz displays run at peak fluidity immediately
 * upon launching the app, preventing the initial 60Hz scroll lag.
 */
fun Activity.enableHighRefreshRate() {
    try {
        val window = window ?: return
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display ?: (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return

        val supportedModes = display.supportedModes ?: return
        if (supportedModes.isEmpty()) return

        val currentMode = display.mode
        val highestRefreshRateMode = supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: supportedModes.maxByOrNull { it.refreshRate }

        highestRefreshRateMode?.let { mode ->
            val params = window.attributes
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
                window.attributes = params
            }
        }
    } catch (_: Exception) {
        // Gracefully ignore on devices that do not permit display mode switching
    }
}

