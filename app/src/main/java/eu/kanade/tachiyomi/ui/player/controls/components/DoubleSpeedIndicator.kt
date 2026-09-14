package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val speedStops = listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f)

@Composable
fun DoubleSpeedIndicator(
    speed: Float,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    var showFullBar by remember { mutableStateOf(false) }

    // Every time speed changes, keep it fully visible. If speed doesn't change
    // for 1.5 seconds, minimize it.
    LaunchedEffect(speed) {
        if (isDragging) {
            showFullBar = true
            delay(1500)
            showFullBar = false
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(36.dp))
            .background(Color.Black.copy(0.4f))
            .animateContentSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (showFullBar) {
            val trackWidth = 240.dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                // Top labels
                Box(
                    modifier = Modifier.width(trackWidth).padding(bottom = 6.dp),
                ) {
                    speedStops.forEach { stopVal ->
                        val labelProgress = ((stopVal - 0.5f) / 3.5f).coerceIn(0f, 1f)
                        val label = String.format("%.1fx", stopVal)
                        Text(
                            text = label,
                            color = if (Math.abs(speed - stopVal) < 0.1f) Color(0xFF4A90E2) else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.offset(x = trackWidth * labelProgress - 12.dp),
                        )
                    }
                }

                // Track with dots
                Box(
                    modifier = Modifier
                        .width(trackWidth)
                        .height(12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Track background
                    Box(
                        modifier = Modifier
                            .width(trackWidth)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.3f)),
                    )
                    // Progress fill
                    val progress = ((speed - 0.5f) / 3.5f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .width(trackWidth * progress)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF4A90E2)),
                    )
                    // Dot markers
                    speedStops.forEach { stopVal ->
                        val dotProgress = ((stopVal - 0.5f) / 3.5f).coerceIn(0f, 1f)
                        val dotOffset = (trackWidth * dotProgress - 3.dp).coerceAtLeast(0.dp)
                        Box(
                            modifier = Modifier
                                .offset(x = dotOffset)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (speed >= stopVal - 0.1f) Color(0xFF4A90E2) else Color.White.copy(alpha = 0.7f)),
                        )
                    }
                    // Thumb
                    val thumbOffset = (trackWidth * progress - 5.dp).coerceAtLeast(0.dp)
                    Box(
                        modifier = Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(thumbOffset.roundToPx(), 0)
                            }
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4A90E2)),
                    )
                }

                // Bottom status text
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val speedText = String.format("%.2fx", speed)
                    Text(
                        text = "$speedText Speed Playing",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            // Minimized View (Only Text)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                val speedText = String.format("%.2fx", speed)
                Text(
                    text = speedText,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
