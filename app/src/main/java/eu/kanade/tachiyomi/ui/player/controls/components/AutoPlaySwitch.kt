package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.controls.LocalPlayerButtonsClickEvent

/**
 * Autoplay toggle as a labelled chip ("Auto"): filled with the accent colour when on,
 * translucent when off. Much clearer than the old bare switch.
 */
@Composable
fun AutoPlaySwitch(
    isChecked: Boolean,
    onToggleAutoPlay: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") iconSize: Dp = 24.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val clickEvent = LocalPlayerButtonsClickEvent.current

    val background by animateColorAsState(
        targetValue = if (isChecked) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
        } else {
            Color.White.copy(alpha = 0.14f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "autoplay_chip_bg",
    )
    val content by animateColorAsState(
        targetValue = if (isChecked) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.88f),
        animationSpec = tween(durationMillis = 220),
        label = "autoplay_chip_fg",
    )

    Row(
        modifier = modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
            ) {
                clickEvent()
                onToggleAutoPlay(!isChecked)
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (isChecked) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Auto",
            color = content,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
