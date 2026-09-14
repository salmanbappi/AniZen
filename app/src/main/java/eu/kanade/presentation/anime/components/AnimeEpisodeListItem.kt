package eu.kanade.presentation.anime.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Row(
        modifier = modifier
            .tvListItemFocusHighlight()
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (previewUrl.isNullOrBlank() && summary.isNullOrBlank()) {
            SimpleEpisodeListItemImpl(
                title = title,
                date = date,
                watchProgress = watchProgress,
                fillermark = fillermark,
                isAutoFiller = isAutoFiller,
                scanlator = scanlator,
                seen = seen,
                bookmark = bookmark,
                downloadIndicatorEnabled = downloadIndicatorEnabled,
                downloadStateProvider = downloadStateProvider,
                downloadProgressProvider = downloadProgressProvider,
                onDownloadClick = onDownloadClick,
                fileSize = fileSize,
            )
            return@Row
        }

        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EpisodeThumbnail(previewUrl = previewUrl)

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val titleLines = if (previewUrl == null) 1 else 2
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 14.sp),
                            modifier = Modifier.weight(1f),
                            maxLines = titleLines,
                            minLines = titleLines,
                            overflow = TextOverflow.Ellipsis,
                            color = LocalContentColor.current.copy(alpha = if (seen) DISABLED_ALPHA else 1f),
                        )

                        if (previewUrl == null) {
                            Column(horizontalAlignment = Alignment.End) {
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
                    }

                    EpisodeSummary(
                        seen = seen,
                        isAnyEpisodeSelected = isAnyEpisodeSelected,
                        summary = summary,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                EpisodeInformation(
                    seen = seen,
                    date = date,
                    watchProgress = watchProgress,
                    fillermark = fillermark,
                    isAutoFiller = isAutoFiller,
                    scanlator = scanlator,
                )

                if (previewUrl != null) {
                    Column(horizontalAlignment = Alignment.End) {
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
            }
        }
    }
}

@Composable
private fun RowScope.SimpleEpisodeListItemImpl(
    title: String,
    date: String?,
    watchProgress: String?,
    fillermark: Boolean,
    isAutoFiller: Boolean,
    scanlator: String?,
    seen: Boolean,
    bookmark: Boolean,
    downloadIndicatorEnabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    onDownloadClick: ((EpisodeDownloadAction) -> Unit)?,
    fileSize: Long?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(if (fillermark) 0.dp else 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = LocalContentColor.current.copy(alpha = if (seen) DISABLED_ALPHA else 1f),
        )

        EpisodeInformation(
            seen = seen,
            date = date,
            watchProgress = watchProgress,
            fillermark = fillermark,
            isAutoFiller = isAutoFiller,
            scanlator = scanlator,
        )
    }

    Column(horizontalAlignment = Alignment.End) {
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
private fun EpisodeThumbnail(
    previewUrl: String?,
) {
    val targetWidth = ((LocalConfiguration.current.screenWidthDp * 0.4f).coerceAtMost(250f))
    if (!previewUrl.isNullOrBlank()) {
        AnimeCover.Square(
            data = previewUrl,
            modifier = Modifier
                .width(targetWidth.dp)
                .padding(end = 8.dp),
            ratio = 16f / 9f,
            shape = MaterialTheme.shapes.small,
        )
    }
}

@Composable
private fun EpisodeSummary(
    seen: Boolean,
    isAnyEpisodeSelected: Boolean,
    summary: String?,
) {
    var expanded by remember { mutableStateOf(false) }
    if (!summary.isNullOrBlank()) {
        Text(
            text = summary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            fontWeight = FontWeight.Normal,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            overflow = TextOverflow.Ellipsis,
            color = LocalContentColor.current.copy(
                alpha = if (seen) DISABLED_ALPHA else SECONDARY_ALPHA,
            ),
            modifier = Modifier
                .padding(bottom = 4.dp, start = 4.dp, end = 4.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isAnyEpisodeSelected,
                    onClick = { expanded = !expanded },
                ),
        )
    }
}

@Composable
private fun EpisodeInformation(
    seen: Boolean,
    date: String?,
    watchProgress: String?,
    fillermark: Boolean,
    isAutoFiller: Boolean,
    scanlator: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val subtitleStyle = MaterialTheme.typography.bodySmall
            .merge(color = LocalContentColor.current.copy(alpha = if (seen) DISABLED_ALPHA else SECONDARY_ALPHA))

        if (!seen) {
            Icon(
                imageVector = Icons.Filled.Circle,
                contentDescription = stringResource(MR.strings.unseen),
                modifier = Modifier
                    .height(8.dp)
                    .padding(end = 4.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (fillermark) {
            Icon(
                imageVector = ImageVector.vectorResource(id = R.drawable.ic_fillermark_24dp),
                contentDescription = stringResource(AMR.strings.action_filter_fillermarked),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
        if (date != null) {
            if (isAutoFiller) {
                Text(
                    text = "f ",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    ),
                )
            }
            Text(
                text = date,
                style = subtitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (watchProgress != null || scanlator != null) {
                Text(text = " • ", style = subtitleStyle)
            }
        }
        if (watchProgress != null) {
            Text(
                text = watchProgress,
                style = subtitleStyle.copy(color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (scanlator != null) {
                Text(text = " • ", style = subtitleStyle)
            }
        }
        if (scanlator != null) {
            Text(
                text = scanlator,
                style = subtitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

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
    Row(
        modifier = modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(SECONDARY_ALPHA),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.alpha(SECONDARY_ALPHA)) {
                ProvideTextStyle(
                    value = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                ) {
                    Text(
                        text = date,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
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
