package eu.kanade.presentation.theme

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.theme.TachiyomiShapes
import eu.kanade.presentation.theme.TachiyomiTypography
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun DynamicTachiyomiTheme(
    animate: Boolean = true,
    colorSeed: Int? = null,
    contrast: Double = 0.0,
    enabled: Boolean = Injekt.get<UiPreferences>().dynamicAnimeTheme().collectAsState().value,
    content: @Composable () -> Unit,
) {
    val uiPreferences = Injekt.get<UiPreferences>()
    val isAmoled by uiPreferences.themeDarkAmoled().collectAsState()
    val isDark = isSystemInDarkTheme()

    if (colorSeed != null && enabled) {
        val colorScheme = rememberDynamicColorScheme(colorSeed, isDark, isAmoled, contrast)
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = TachiyomiShapes,
            typography = TachiyomiTypography,
            content = content,
        )
    } else {
        // Fallback to standard theme
        TachiyomiTheme(content = content)
    }
}

@Composable
private fun rememberDynamicColorScheme(
    seed: Int,
    isDark: Boolean,
    isAmoled: Boolean,
    contrast: Double,
): ColorScheme {
    val colorScheme = remember(seed, isDark, isAmoled, contrast) {
        val scheme = generateColorSchemeFromSeed(seed, isDark, contrast)
        if (isDark && isAmoled) {
            scheme.copy(
                background = Color.Black,
                onBackground = Color.White,
                surface = Color.Black,
                onSurface = Color.White,
                surfaceVariant = Color(0xFF0C0C0C),
                surfaceContainerLowest = Color(0xFF0C0C0C),
                surfaceContainerLow = Color(0xFF0C0C0C),
                surfaceContainer = Color(0xFF0C0C0C),
                surfaceContainerHigh = Color(0xFF131313),
                surfaceContainerHighest = Color(0xFF1B1B1B),
            )
        } else {
            scheme
        }
    }
    return colorScheme
}

private val dynamicColorSchemeCache = androidx.collection.LruCache<Long, ColorScheme>(64)

@SuppressLint("RestrictedApi")
private fun generateColorSchemeFromSeed(seed: Int, dark: Boolean, contrast: Double): ColorScheme {
    val cacheKey = (seed.toLong() and 0xFFFFFFFFL) or (if (dark) 1L else 0L shl 32) or (contrast.toRawBits() shl 33)
    val cached = dynamicColorSchemeCache.get(cacheKey)
    if (cached != null) return cached

    val scheme = SchemeContent(
        Hct.fromInt(seed),
        dark,
        contrast,
    )
    val dynamicColors = MaterialDynamicColors()
    val result = ColorScheme(
        primary = Color(dynamicColors.primary().getArgb(scheme)),
        onPrimary = Color(dynamicColors.onPrimary().getArgb(scheme)),
        primaryContainer = Color(dynamicColors.primaryContainer().getArgb(scheme)),
        onPrimaryContainer = Color(dynamicColors.onPrimaryContainer().getArgb(scheme)),
        inversePrimary = Color(dynamicColors.inversePrimary().getArgb(scheme)),
        secondary = Color(dynamicColors.secondary().getArgb(scheme)),
        onSecondary = Color(dynamicColors.onSecondary().getArgb(scheme)),
        secondaryContainer = Color(dynamicColors.secondaryContainer().getArgb(scheme)),
        onSecondaryContainer = Color(dynamicColors.onSecondaryContainer().getArgb(scheme)),
        tertiary = Color(dynamicColors.tertiary().getArgb(scheme)),
        onTertiary = Color(dynamicColors.onTertiary().getArgb(scheme)),
        tertiaryContainer = Color(dynamicColors.tertiary().getArgb(scheme)),
        onTertiaryContainer = Color(dynamicColors.onTertiaryContainer().getArgb(scheme)),
        background = Color(dynamicColors.background().getArgb(scheme)),
        onBackground = Color(dynamicColors.onBackground().getArgb(scheme)),
        surface = Color(dynamicColors.surface().getArgb(scheme)),
        onSurface = Color(dynamicColors.onSurface().getArgb(scheme)),
        surfaceVariant = Color(dynamicColors.surfaceVariant().getArgb(scheme)),
        onSurfaceVariant = Color(dynamicColors.onSurfaceVariant().getArgb(scheme)),
        surfaceTint = Color(dynamicColors.surfaceTint().getArgb(scheme)),
        inverseSurface = Color(dynamicColors.inverseSurface().getArgb(scheme)),
        inverseOnSurface = Color(dynamicColors.inverseOnSurface().getArgb(scheme)),
        error = Color(dynamicColors.error().getArgb(scheme)),
        onError = Color(dynamicColors.onError().getArgb(scheme)),
        errorContainer = Color(dynamicColors.errorContainer().getArgb(scheme)),
        onErrorContainer = Color(dynamicColors.onErrorContainer().getArgb(scheme)),
        outline = Color(dynamicColors.outline().getArgb(scheme)),
        outlineVariant = Color(dynamicColors.outlineVariant().getArgb(scheme)),
        scrim = Color.Black,
        surfaceBright = Color(dynamicColors.surfaceBright().getArgb(scheme)),
        surfaceDim = Color(dynamicColors.surfaceDim().getArgb(scheme)),
        surfaceContainer = Color(dynamicColors.surfaceContainer().getArgb(scheme)),
        surfaceContainerHigh = Color(dynamicColors.surfaceContainerHigh().getArgb(scheme)),
        surfaceContainerHighest = Color(dynamicColors.surfaceContainerHighest().getArgb(scheme)),
        surfaceContainerLow = Color(dynamicColors.surfaceContainerLow().getArgb(scheme)),
        surfaceContainerLowest = Color(dynamicColors.surfaceContainerLowest().getArgb(scheme)),
    )
    dynamicColorSchemeCache.put(cacheKey, result)
    return result
}
