// ANZ -->
package eu.kanade.presentation.anilist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.anilist.AniListMedia
import kotlinx.coroutines.delay

@Composable
fun HeroCarousel(
    mediaList: List<AniListMedia>,
    modifier: Modifier = Modifier,
) {
    if (mediaList.isEmpty()) return

    val navigator = LocalNavigator.currentOrThrow
    val pagerState = rememberPagerState { mediaList.size }
    var selectedMediaForWatchWith by remember { mutableStateOf<AniListMedia?>(null) }

    if (selectedMediaForWatchWith != null) {
        WatchWithBottomSheet(
            media = selectedMediaForWatchWith!!,
            onDismissRequest = { selectedMediaForWatchWith = null },
        )
    }

    // Auto-advance timer (4.5s), pauses when user is dragging or touching
    LaunchedEffect(pagerState, mediaList.size) {
        while (true) {
            delay(4500)
            if (!pagerState.isScrollInProgress && mediaList.size > 1) {
                val nextPage = (pagerState.currentPage + 1) % mediaList.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val media = mediaList.getOrNull(page) ?: return@HorizontalPager
                val imageUrl = if (!media.bannerImage.isNullOrBlank()) media.bannerImage else media.displayCover

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { navigator.push(AniListDetailsScreen(media)) },
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = media.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Dark gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.5f),
                                        Color.Black.copy(alpha = 0.95f),
                                    ),
                                ),
                            ),
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = media.displayTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        val metaParts = mutableListOf<String>()
                        if (media.seasonYear != null) metaParts.add("${media.seasonYear}")
                        if (!media.season.isNullOrBlank()) metaParts.add(media.season)
                        if (media.averageScore != null && media.averageScore > 0) metaParts.add("★ ${media.averageScore}%")
                        if (metaParts.isNotEmpty()) {
                            Text(
                                text = metaParts.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Button(
                                onClick = { selectedMediaForWatchWith = media },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Watch Now")
                            }

                            OutlinedButton(
                                onClick = { navigator.push(AniListDetailsScreen(media)) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(imageVector = Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Details")
                            }
                        }
                    }
                }
            }

            // Page Indicator Dots
            if (mediaList.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(mediaList.size) { index ->
                        val active = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (active) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                                ),
                        )
                    }
                }
            }
        }
    }
}
// ANZ <--
