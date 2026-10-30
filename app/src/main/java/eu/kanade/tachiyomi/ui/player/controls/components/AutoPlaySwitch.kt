package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Autoplay toggle as an icon-only glass button: lit with the accent colour and showing "play" when
 * autoplay is on, neutral glass showing "pause" when it is off. The icon cross-fades when toggled.
 */
@Composable
fun AutoPlaySwitch(
    isChecked: Boolean,
    onToggleAutoPlay: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") iconSize: Dp = 24.dp,
) {
    GlassIconButton(
        icon = if (isChecked) Icons.Filled.PlayArrow else Icons.Filled.Pause,
        contentDescription = "Reproducción automática",
        onClick = { onToggleAutoPlay(!isChecked) },
        modifier = modifier,
        diameter = 38.dp,
        iconSize = 22.dp,
        active = isChecked,
    )
}
