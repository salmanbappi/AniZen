package eu.kanade.presentation.anime.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.presentation.core.util.tvListItemFocusHighlight

@Composable
fun AnimeEpisodeListItem(
    title: String,
    date: String?,
    watchProgress: String?,
    scanlator: String?,
    seen: Boolean,
    bookmark: Boolean,
    fillermark: Boolean,
    isAutoFiller: Boolean = false,
    summary: String?,
    previewUrl: String?,
    selected: Boolean,
    isAnyEpisodeSelected: Boolean,
    downloadIndicatorEnabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDownloadClick: ((EpisodeDownloadAction) -> Unit)?,
    onEpisodeSwipe: (LibraryPreferences.EpisodeSwipeAction) -> Unit,
    // AM (FILE_SIZE) -->
    fileSize: Long?,
    // <-- AM (FILE_SIZE)
    modifier: Modifier = Modifier,
) {
    val downloadState = downloadStateProvider()
    val fillermarkIcon = ImageVector.vectorResource(id = R.drawable.ic_fillermark_24dp)
    val fillermarkBorderIcon = ImageVector.vectorResource(id = R.drawable.ic_fillermark_border_24dp)
    // Hoist @Composable color read out of remember lambdas
    val swipeBackground = MaterialTheme.colorScheme.primaryContainer

    val start = remember(episodeSwipeStartAction, seen, bookmark, fillermark, downloadState, onEpisodeSwipe, fillermarkIcon, fillermarkBorderIcon) {
        getSwipeAction(
            action = episodeSwipeStartAction,
            seen = seen,
            bookmark = bookmark,
            fillermark = fillermark,
            downloadState = downloadState,
            background = swipeBackground,
            onSwipe = { onEpisodeSwipe(episodeSwipeStartAction) },
            fillermarkIcon = fillermarkIcon,
            fillermarkBorderIcon = fillermarkBorderIcon,
        )
    }
    val end = remember(episodeSwipeEndAction, seen, bookmark, fillermark, downloadState, onEpisodeSwipe, fillermarkIcon, fillermarkBorderIcon) {
        getSwipeAction(
            action = episodeSwipeEndAction,
            seen = seen,
            bookmark = bookmark,
            fillermark = fillermark,
            downloadState = downloadState,
            background = swipeBackground,
            onSwipe = { onEpisodeSwipe(episodeSwipeEndAction) },
            fillermarkIcon = fillermarkIcon,
            fillermarkBorderIcon = fillermarkBorderIcon,
        )
    }

    val hasActions = start != null || end != null

    if (hasActions) {
        SwipeableActionsBox(
            modifier = modifier.clipToBounds(),
            startActions = listOfNotNull(start),
            endActions = listOfNotNull(end),
            swipeThreshold = swipeActionThreshold,
            backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            EpisodeItemRow(
                title = title,
                date = date,
                watchProgress = watchProgress,
                scanlator = scanlator,
                seen = seen,
                bookmark = bookmark,
                fillermark = fillermark,
                isAutoFiller = isAutoFiller,
                summary = summary,
                previewUrl = previewUrl,
                selected = selected,
                isAnyEpisodeSelected = isAnyEpisodeSelected,
                downloadIndicatorEnabled = downloadIndicatorEnabled,
                downloadStateProvider = downloadStateProvider,
                downloadProgressProvider = downloadProgressProvider,
                onLongClick = onLongClick,
                onClick = onClick,
                onDownloadClick = onDownloadClick,
                fileSize = fileSize,
            )
        }
    } else {
        EpisodeItemRow(
            title = title,
            date = date,
            watchProgress = watchProgress,
            scanlator = scanlator,
            seen = seen,
            bookmark = bookmark,
            fillermark = fillermark,
            isAutoFiller = isAutoFiller,
            summary = summary,
            previewUrl = previewUrl,
            selected = selected,
            isAnyEpisodeSelected = isAnyEpisodeSelected,
            downloadIndicatorEnabled = downloadIndicatorEnabled,
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onLongClick = onLongClick,
            onClick = onClick,
            onDownloadClick = onDownloadClick,
            fileSize = fileSize,
            modifier = modifier,
        )
    }
}

// ANZ -->
/*
 * Ultra-minimal "streaming" row: clean rounded thumbnail on the left, big typographic episode
 * title on the right. No play badge, no date, no seen check. Seen episodes simply fade, the one
 * in progress gets an accent ring + its progress text, and every row has a soft entrance and a
 * springy press effect (row squeezes, thumbnail zooms).
 */
private const val SeenAlpha = 0.5f
private val ThumbShape = RoundedCornerShape(16.dp)
private val EpisodeTitleRegex = Regex("""^\s*(\p{L}[\p{L}\s.]*?)\s*(\d+(?:[.,]\d+)?)\s*$""")

/** "Episodio 12" -> ("Episodio", "12"); anything that does not fit that shape -> null. */
private fun splitEpisodeTitle(title: String): Pair<String, String>? {
    val match = EpisodeTitleRegex.find(title) ?: return null
    return match.groupValues[1].trim() to match.groupValues[2]
}

@Composable
private fun EpisodeItemRow(
    title: String,
    date: String?,
    watchProgress: String?,
    scanlator: String?,
    seen: Boolean,
    bookmark: Boolean,
    fillermark: Boolean,
    isAutoFiller: Boolean,
    summary: String?,
    previewUrl: String?,
    selected: Boolean,
    isAnyEpisodeSelected: Boolean,
    downloadIndicatorEnabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDownloadClick: ((EpisodeDownloadAction) -> Unit)?,
    fileSize: Long?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "episodePress",
    )
    val zoom by animateFloatAsState(
        targetValue = if (pressed) 1.08f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "episodeZoom",
    )
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }

    val parts = remember(title) { splitEpisodeTitle(title) }
    val inProgress = !seen && watchProgress != null
    val isFiller = fillermark || isAutoFiller

    Row(
        modifier = modifier
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 14.dp.toPx()
                scaleX = pressScale
                scaleY = pressScale
            }
            .tvListItemFocusHighlight()
            .selectedBackground(selected)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!previewUrl.isNullOrBlank()) {
            EpisodeThumbnail(
                previewUrl = previewUrl,
                seen = seen,
                inProgress = inProgress,
                zoom = { zoom },
            )
        } else {
            EpisodePlaceholder(number = parts?.second, seen = seen, inProgress = inProgress)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            EpisodeTitle(title = title, parts = parts, seen = seen, inProgress = inProgress)
            if (inProgress) {
                Text(
                    text = watchProgress.orEmpty(),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFiller) {
                FillerTag(modifier = Modifier.padding(top = 4.dp))
            }
        }

        BookmarkDownloadIcons(
            bookmark = bookmark,
            downloadIndicatorEnabled = downloadIndicatorEnabled,
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onDownloadClick = onDownloadClick,
            fileSize = fileSize,
        )
    }
}

@Composable
private fun thumbnailWidth() = (LocalConfiguration.current.screenWidthDp * 0.36f).coerceIn(120f, 176f).dp

/** Clean 16:9 thumbnail: no overlays, just a hairline glass edge (accent ring while in progress). */
@Composable
private fun EpisodeThumbnail(
    previewUrl: String,
    seen: Boolean,
    inProgress: Boolean,
    zoom: () -> Float,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .width(thumbnailWidth())
            .clip(ThumbShape)
            .border(
                width = if (inProgress) 2.dp else 1.dp,
                color = if (inProgress) colors.primary else Color.White.copy(alpha = 0.10f),
                shape = ThumbShape,
            ),
    ) {
        AnimeCover.Square(
            data = previewUrl,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val z = zoom()
                    scaleX = z
                    scaleY = z
                    alpha = if (seen) SeenAlpha else 1f
                },
            ratio = 16f / 9f,
            shape = ThumbShape,
        )
    }
}

/** Same footprint as a thumbnail, for episodes without a preview image. */
@Composable
private fun EpisodePlaceholder(number: String?, seen: Boolean, inProgress: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .width(thumbnailWidth())
            .aspectRatio(16f / 9f)
            .clip(ThumbShape)
            .background(
                Brush.linearGradient(
                    listOf(colors.primary.copy(alpha = 0.34f), colors.secondary.copy(alpha = 0.12f)),
                ),
            )
            .border(
                width = if (inProgress) 2.dp else 1.dp,
                color = if (inProgress) colors.primary else Color.White.copy(alpha = 0.10f),
                shape = ThumbShape,
            )
            .alpha(if (seen) SeenAlpha else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number ?: "•",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = colors.onSurface.copy(alpha = 0.8f),
            maxLines = 1,
        )
    }
}

/** Small caps label + big numeral ("EPISODIO" / "12"); falls back to the plain title. */
@Composable
private fun EpisodeTitle(
    title: String,
    parts: Pair<String, String>?,
    seen: Boolean,
    inProgress: Boolean,
) {
    val base = LocalContentColor.current
    val fade = if (seen) SeenAlpha else 1f
    if (parts != null) {
        Text(
            text = parts.first.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.8.sp,
            ),
            color = base.copy(alpha = 0.6f * fade),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = parts.second,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
            ),
            color = if (inProgress) MaterialTheme.colorScheme.primary else base.copy(alpha = fade),
            maxLines = 1,
        )
    } else {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = base.copy(alpha = fade),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FillerTag(modifier: Modifier = Modifier) {
    val tertiary = MaterialTheme.colorScheme.tertiary
    Text(
        text = "RELLENO",
        modifier = modifier
            .background(tertiary.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        ),
        color = tertiary,
        maxLines = 1,
    )
}
// ANZ <--

@Composable
private fun BookmarkDownloadIcons(
    bookmark: Boolean,
    downloadIndicatorEnabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    onDownloadClick: ((EpisodeDownloadAction) -> Unit)?,
    fileSize: Long?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (bookmark) {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        EpisodeDownloadIndicator(
            enabled = downloadIndicatorEnabled,
            modifier = Modifier
                .padding(start = 4.dp)
                .sizeIn(minWidth = 40.dp, minHeight = 40.dp),
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onClick = { onDownloadClick?.invoke(it) },
            fileSize = fileSize,
        )
    }
}

// AM (FILLERMARK) -->
// <-- AM (FILLERMARK)
private fun getSwipeAction(
    action: LibraryPreferences.EpisodeSwipeAction,
    seen: Boolean,
    bookmark: Boolean,
    // AM (FILLERMARK) -->
    fillermark: Boolean,
    // <-- AM (FILLERMARK)
    downloadState: Download.State,
    background: Color,
    onSwipe: () -> Unit,
    fillermarkIcon: ImageVector,
    fillermarkBorderIcon: ImageVector,
): me.saket.swipe.SwipeAction? {
    return when (action) {
        LibraryPreferences.EpisodeSwipeAction.ToggleSeen -> swipeAction(
            icon = if (!seen) Icons.Outlined.Done else Icons.Outlined.RemoveDone,
            background = background,
            isUndo = seen,
            onSwipe = onSwipe,
        )
        LibraryPreferences.EpisodeSwipeAction.ToggleBookmark -> swipeAction(
            icon = if (!bookmark) Icons.Outlined.BookmarkAdd else Icons.Outlined.BookmarkRemove,
            background = background,
            isUndo = bookmark,
            onSwipe = onSwipe,
        )
        // AM (FILLERMARK) -->
        LibraryPreferences.EpisodeSwipeAction.ToggleFillermark -> {
            val icon = if (!fillermark) fillermarkIcon else fillermarkBorderIcon
            swipeAction(
                icon = icon,
                background = background,
                isUndo = bookmark,
                onSwipe = onSwipe,
            )
        }
        // <-- AM (FILLERMARK)
        LibraryPreferences.EpisodeSwipeAction.Download -> swipeAction(
            icon = when (downloadState) {
                Download.State.NOT_DOWNLOADED, Download.State.ERROR, Download.State.PAUSED -> Icons.Outlined.Download
                Download.State.QUEUE, Download.State.DOWNLOADING, Download.State.MERGING, Download.State.DECRYPTING, Download.State.FINALIZING -> Icons.Outlined.FileDownloadOff
                Download.State.DOWNLOADED -> Icons.Outlined.Delete
            },
            background = background,
            onSwipe = onSwipe,
        )
        LibraryPreferences.EpisodeSwipeAction.Disabled -> null
    }
}

@Composable
fun NextEpisodeAiringListItem(
    title: String,
    date: String,
    modifier: Modifier = Modifier,
) {
    // ANZ -->
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = date,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // ANZ <--
}

private fun swipeAction(
    onSwipe: () -> Unit,
    icon: ImageVector,
    background: Color,
    isUndo: Boolean = false,
): me.saket.swipe.SwipeAction {
    return me.saket.swipe.SwipeAction(
        icon = {
            Icon(
                modifier = Modifier.padding(16.dp),
                imageVector = icon,
                tint = contentColorFor(background),
                contentDescription = null,
            )
        },
        background = background,
        onSwipe = onSwipe,
        isUndo = isUndo,
    )
}

private val swipeActionThreshold = 56.dp
