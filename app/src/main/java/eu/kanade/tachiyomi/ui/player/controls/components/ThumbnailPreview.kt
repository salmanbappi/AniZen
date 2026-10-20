package eu.kanade.tachiyomi.ui.player.controls.components

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.Utils

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun ThumbnailPreview(
    visible: Boolean,
    image: ImageBitmap?,
    positionSProvider: () -> Long,
    durationS: Long,
    chapters: List<Segment>,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val (previewWidth, previewHeight) =
        remember(image) {
            if (image != null) {
                val width = image.width.toFloat()
                val height = image.height.toFloat().coerceAtLeast(1f)
                val aspectRatio = width / height

                if (aspectRatio < 16f / 9f) {
                    124.dp * aspectRatio to 124.dp
                } else {
                    220.dp to 124.dp
                }
            } else {
                220.dp to 124.dp
            }
        }

    // Reactively compute the seeking chapter / time only when they change, without recomposing
    // the entire ThumbnailPreview on every position tick.
    val seekingChapter by remember(chapters) {
        derivedStateOf {
            chapters.lastOrNull { it.start <= positionSProvider() }
        }
    }
    // ANZ -->
    val seekTime by remember { derivedStateOf { Utils.prettyTime(positionSProvider().toInt()) } }
    // ANZ <--

    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val startInset = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endInset = safeDrawingPadding.calculateEndPadding(layoutDirection)

    AnimatedVisibility(
        visible = visible && image != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        if (image != null) {
            BoxWithConstraints {
                val screenWidth = maxWidth
                // ANZ --> timer slot + the gap between the timer and the seekbar
                val timerWidth = videoTimerWidth + 4.dp
                // ANZ <--

                // Subtract insets from seekbarWidth to precisely align the preview card
                val seekbarWidth = screenWidth - startInset - endInset - timerWidth - timerWidth
                // ANZ --> the thumb travels between thumbRadius and (width - thumbRadius)
                val trackWidth = seekbarWidth - seekerThumbRadius * 2
                // ANZ <--

                Card(
                    modifier = Modifier
                        .offset {
                            val positionS = positionSProvider()
                            val progress = if (durationS > 0L) {
                                (positionS.toFloat() / durationS.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }

                            val targetX = if (layoutDirection == LayoutDirection.Ltr) {
                                startInset + timerWidth + seekerThumbRadius + (trackWidth * progress)
                            } else {
                                (screenWidth - startInset - timerWidth) - seekerThumbRadius - (trackWidth * progress)
                            }

                            val minX = startInset + 8.dp
                            val maxX = screenWidth - previewWidth - endInset - 8.dp

                            val constrainedX = when {
                                targetX - previewWidth / 2 < minX -> minX
                                targetX + previewWidth / 2 > screenWidth - endInset - 8.dp -> maxX
                                else -> targetX - previewWidth / 2
                            }
                            IntOffset(constrainedX.roundToPx(), 0)
                        }
                        .size(previewWidth, previewHeight),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    // ANZ --> rounder card with a fine light border
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    // ANZ <--
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )

                        // ANZ --> soft fade + big time (and chapter name) at the bottom
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(72.dp)
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.82f),
                                    ),
                                ),
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AnimatedContent<Segment?>(
                                targetState = seekingChapter,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(200)) togetherWith
                                        fadeOut(animationSpec = tween(200)) using
                                        SizeTransform(clip = false)
                                },
                                contentAlignment = Alignment.Center,
                                label = "ChapterNameAnimation",
                            ) { chapterName ->
                                if (chapterName?.name?.isEmpty() == false) {
                                    Text(
                                        text = chapterName.name,
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                text = seekTime,
                                color = Color.White,
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum",
                                ),
                            )
                        }
                        // ANZ <--
                    }
                }
            }
        }
    }
}
