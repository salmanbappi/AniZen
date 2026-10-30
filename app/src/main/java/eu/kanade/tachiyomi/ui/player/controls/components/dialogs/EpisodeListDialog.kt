package eu.kanade.tachiyomi.ui.player.controls.components.dialogs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.data.database.models.Episode
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.format.DateTimeFormatter

// ANZ -->
/*
 * Streaming-style episode picker: a grid of 16:9 thumbnail cards (the same preview image the anime
 * screen uses), newest first. The episode being played is ringed in the accent colour with a play
 * badge, watched episodes fade, partly watched ones show a thin progress bar. No dates.
 */
private val CardShape = RoundedCornerShape(16.dp)
private const val SeenAlpha = 0.5f
private val EpisodeTitleRegex = Regex("""^\s*(\p{L}[\p{L}\s.]*?)\s*(\d+(?:[.,]\d+)?)\s*$""")

/** "Episodio 12" -> ("Episodio", "12"); anything that does not fit that shape -> null. */
private fun splitEpisodeTitle(title: String): Pair<String, String>? {
    val match = EpisodeTitleRegex.find(title) ?: return null
    return match.groupValues[1].trim() to match.groupValues[2]
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun EpisodeListDialog(
    displayMode: Long?,
    currentEpisodeIndex: Int,
    episodeList: List<Episode>,
    dateRelativeTime: Boolean,
    dateFormat: DateTimeFormatter,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val itemScrollIndex = ((episodeList.size - currentEpisodeIndex) - 1)
        .coerceIn(0, (episodeList.size - 1).coerceAtLeast(0))
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = itemScrollIndex)
    val reversedList = remember(episodeList) { episodeList.reversed() }
    val current = episodeList.getOrNull(currentEpisodeIndex)

    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(fraction = 0.92f).fillMaxWidth(fraction = 0.84f),
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = colors.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.episodes),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = episodeList.size.toString(),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.primary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismissRequest) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 200.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = reversedList,
                        key = { index, it -> "ep-${it.id}-$index" },
                        contentType = { _, _ -> "episode" },
                    ) { _, episode ->
                        val title = if (displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                            stringResource(
                                MR.strings.display_mode_episode,
                                formatEpisodeNumber(episode.episode_number.toDouble()),
                            )
                        } else {
                            episode.name
                        }
                        EpisodeCard(
                            episode = episode,
                            isCurrentEpisode = episode.id == current?.id,
                            title = title,
                            onBookmarkClicked = onBookmarkClicked,
                            onEpisodeClicked = onEpisodeClicked,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    isCurrentEpisode: Boolean,
    title: String,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var isBookmarked by remember(episode.id) { mutableStateOf(episode.bookmark) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "episodeCardPress",
    )
    val seen = episode.seen
    val previewUrl = episode.preview_url
    val parts = remember(title) { splitEpisodeTitle(title) }
    val progress = if (!seen && episode.total_seconds > 0L && episode.last_second_seen > 0L) {
        (episode.last_second_seen.toFloat() / episode.total_seconds.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val fade = if (seen) SeenAlpha else 1f

    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(interactionSource = interaction, indication = null) {
                onEpisodeClicked(episode.id)
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .border(
                    width = if (isCurrentEpisode) 2.dp else 1.dp,
                    color = if (isCurrentEpisode) colors.primary else Color.White.copy(alpha = 0.10f),
                    shape = CardShape,
                ),
        ) {
            if (!previewUrl.isNullOrBlank()) {
                AnimeCover.Square(
                    data = previewUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = fade },
                    ratio = 16f / 9f,
                    shape = CardShape,
                    measureRatio = false,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(
                            Brush.linearGradient(
                                listOf(colors.primary.copy(alpha = 0.34f), colors.secondary.copy(alpha = 0.12f)),
                            ),
                        )
                        .graphicsLayer { alpha = fade },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = parts?.second ?: "•",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = colors.onSurface.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }

            // the episode being played: soft dim + play badge
            if (isCurrentEpisode) {
                Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // thin progress bar for partly watched episodes
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(colors.primary),
                    )
                }
            }

            // bookmark toggle, as a small glass button on the corner
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable {
                        val bookmarked = !isBookmarked
                        episode.bookmark = bookmarked
                        isBookmarked = bookmarked
                        onBookmarkClicked(episode.id, bookmarked)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                    contentDescription = null,
                    tint = if (isBookmarked) colors.primary else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            val titleColor = if (isCurrentEpisode) colors.primary else colors.onSurface.copy(alpha = fade)
            if (parts != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = parts.first.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.6.sp,
                        ),
                        color = titleColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Text(
                        text = parts.second,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = titleColor,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val scanlator = episode.scanlator
            if (!scanlator.isNullOrBlank()) {
                Text(
                    text = scanlator,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface.copy(alpha = 0.55f * fade),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
// ANZ <--
