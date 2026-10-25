package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SlideToUnlock(
    onUnlock: () -> Unit,
    onDraggingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var sliderWidth by remember { mutableStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val buttonSize = 48.dp
    val buttonSizePx = with(density) { buttonSize.toPx() }

    val maxOffset = if (sliderWidth > 0) sliderWidth - buttonSizePx else 0f
    val unlockThreshold = maxOffset * 0.85f

    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = tween(durationMillis = if (isDragging) 0 else 300),
        label = "sliderOffset",
    )

    LaunchedEffect(isDragging) {
        onDraggingChanged(isDragging)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth(0.5f)
            .height(56.dp)
            .onSizeChanged { sliderWidth = it.width },
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.5f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Background Text
            val textAlpha = 1f - (animatedOffsetX / (maxOffset.coerceAtLeast(1f)))
            Text(
                text = "Slide to Unlock",
                color = Color.White.copy(alpha = textAlpha.coerceIn(0f, 1f)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )

            // Draggable Button
            Box(
                modifier = Modifier
                    .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                    .size(buttonSize)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            isDragging = true
                            offsetX = (offsetX + delta).coerceIn(0f, maxOffset)
                        },
                        onDragStopped = {
                            isDragging = false
                            if (offsetX > unlockThreshold && maxOffset > 0) {
                                onUnlock()
                            }
                            offsetX = 0f
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = offsetX > unlockThreshold / 2f,
                    label = "lockIcon",
                ) { isUnlocking ->
                    Icon(
                        imageVector = if (isUnlocking) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = "Unlock",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
