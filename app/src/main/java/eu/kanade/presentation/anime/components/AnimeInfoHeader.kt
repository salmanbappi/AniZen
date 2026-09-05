package eu.kanade.presentation.anime.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.HourglassDisabled
import eu.kanade.tachiyomi.source.getNameForAnimeInfo
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import eu.kanade.presentation.anime.components.MarkdownRender
import eu.kanade.presentation.anime.components.DISALLOWED_MARKDOWN_TYPES
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.ui.anime.track.TrackItem
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.presentation.anime.components.RatioSwitchToPanorama
import eu.kanade.tachiyomi.util.system.CoverColorObserver
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import tachiyomi.presentation.core.util.tvFocusHighlight
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Composable
fun AnimeInfoBox(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    anime: Anime,
    totalScore: Double?,
    sourceName: String,
    isStubSource: Boolean,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    mergedSources: ImmutableList<eu.kanade.tachiyomi.source.Source> = persistentListOf(),
    isRefreshing: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f)),
    ) {
        // Backdrop
        val backgroundColor = MaterialTheme.colorScheme.background
        val backdropGradientColors = remember(backgroundColor) {
            listOf(
                Color.Transparent,
                backgroundColor,
            )
        }
        val backdropBrush = remember(backdropGradientColors) {
            Brush.verticalGradient(colors = backdropGradientColors)
        }
        val context = LocalContext.current
        val uiPreferences = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
        val animatedTransitions by uiPreferences.animatedTransitions().collectAsState()
        val backdropImageRequest = remember(anime.id, anime.thumbnailUrl, anime.coverLastModified, animatedTransitions) {
            ImageRequest.Builder(context)
                .data(anime.asAnimeCover())
                .precision(coil3.size.Precision.INEXACT)
                .crossfade(animatedTransitions)
                .build()
        }
        AsyncImage(
            model = backdropImageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .clipToBounds()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = backdropBrush)
                }
                .blur(4.dp)
                .graphicsLayer {
                    alpha = 0.25f
                },
        )

        // Anime & source info
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            if (!isTabletUi) {
                AnimeAndSourceTitlesSmall(
                    appBarPadding = appBarPadding,
                    anime = anime,
                    totalScore = totalScore,
                    sourceName = sourceName,
                    isStubSource = isStubSource,
                    onCoverClick = onCoverClick,
                    doSearch = doSearch,
                    mergedSources = mergedSources,
                    isRefreshing = isRefreshing,
                )
            } else {
                AnimeAndSourceTitlesLarge(
                    appBarPadding = appBarPadding,
                    anime = anime,
                    totalScore = totalScore,
                    sourceName = sourceName,
                    isStubSource = isStubSource,
                    onCoverClick = onCoverClick,
                    doSearch = doSearch,
                    mergedSources = mergedSources,
                    isRefreshing = isRefreshing,
                )
            }
        }
    }
}

@Composable
fun AnimeActionRow(
    favorite: Boolean,
    trackingCount: Int,
    nextUpdate: Instant?,
    isUserIntervalMode: Boolean,
    fetchInterval: Int,
    status: Long,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,
    onEditIntervalClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    onEditCategory: (() -> Unit)?,
    onContinueWatching: () -> Unit,
    isWatching: Boolean,
    mainTrackItem: eu.kanade.tachiyomi.ui.anime.track.TrackItem? = null,
    modifier: Modifier = Modifier,
) {
    val defaultActionButtonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    val nextUpdateCountdown = remember(nextUpdate) {
        if (nextUpdate == null) return@remember null
        val now = Instant.now()
        val diffSeconds = now.until(nextUpdate, ChronoUnit.SECONDS)
        if (diffSeconds <= 0) {
            null
        } else {
            val hours = diffSeconds / 3600
            val minutes = (diffSeconds % 3600) / 60
            when {
                hours >= 48 -> null
                hours >= 24 -> {
                    val days = (hours / 24).toInt()
                    val remHours = (hours % 24).toInt()
                    if (remHours > 0) "${days}d ${remHours}h" else "${days}d"
                }
                hours > 0 -> "${hours}h ${minutes}m"
                else -> "${minutes}m"
            }
        }
    }

    val nextUpdateDays = remember(nextUpdate) {
        return@remember if (nextUpdate != null) {
            val now = Instant.now()
            now.until(nextUpdate, ChronoUnit.DAYS).toInt().coerceAtLeast(0)
        } else {
            null
        }
    }

    Column(
        modifier = modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimeActionButton(
                    title = if (favorite) stringResource(MR.strings.in_library) else stringResource(MR.strings.add_to_library),
                    icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    color = if (favorite) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
                    onClick = onAddToLibraryClicked,
                    onLongClick = onEditCategory,
                )
                AnimeActionButton(
                    title = if (status == SAnime.COMPLETED.toLong()) {
                        stringResource(MR.strings.not_applicable)
                    } else if (fetchInterval == tachiyomi.domain.anime.interactor.FetchInterval.MANUAL_DISABLE) {
                        stringResource(MR.strings.disabled)
                    } else {
                        nextUpdateCountdown ?: when (nextUpdateDays) {
                            null -> stringResource(MR.strings.not_applicable)
                            0 -> stringResource(MR.strings.manga_interval_expected_update_soon)
                            else -> pluralStringResource(
                                MR.plurals.day,
                                count = nextUpdateDays,
                                nextUpdateDays
                            )
                        }
                    },
                    icon = if (fetchInterval == tachiyomi.domain.anime.interactor.FetchInterval.MANUAL_DISABLE) {
                        Icons.Outlined.HourglassDisabled
                    } else {
                        Icons.Default.HourglassEmpty
                    },
                    color = if (isUserIntervalMode) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
                    onClick = { if (favorite) onEditIntervalClicked?.invoke() },
                )
                AnimeActionButton(
                    title = run {
                        if (mainTrackItem?.track != null) {
                            val status = (mainTrackItem.tracker as? eu.kanade.tachiyomi.data.track.AnimeTracker)?.getStatusForAnime(mainTrackItem.track.status)
                            val statusText = status?.let { stringResource(it) } ?: ""
                            statusText
                        } else if (trackingCount == 0) {
                            stringResource(MR.strings.manga_tracking_tab)
                        } else {
                            pluralStringResource(MR.plurals.num_trackers, count = trackingCount, trackingCount)
                        }
                    },
                    icon = if (mainTrackItem?.track != null || trackingCount > 0) {
                        Icons.Outlined.Done
                    } else {
                        Icons.Outlined.Sync
                    },
                    color = if (trackingCount == 0) defaultActionButtonColor else MaterialTheme.colorScheme.primary,
                    onClick = onTrackingClicked,
                )
                if (onWebViewClicked != null) {
                    AnimeActionButton(
                        title = stringResource(MR.strings.action_web_view),
                        icon = Icons.Outlined.Public,
                        color = defaultActionButtonColor,
                        onClick = onWebViewClicked,
                        onLongClick = onWebViewLongClicked,
                    )
                }
            }
        }
    }
}

@Composable
fun ExpandableAnimeDescription(
    defaultExpandState: Boolean,
    description: String?,
    note: String?,
    tagsProvider: () -> List<String>?,
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (expanded, onExpanded) = rememberSaveable {
        mutableStateOf(defaultExpandState)
    }
    Column(modifier = modifier) {
        val desc =
            description.takeIf { !it.isNullOrBlank() } ?: stringResource(MR.strings.description_placeholder)

        if (!note.isNullOrBlank()) {
            MarkdownRender(
                content = note,
                modifier = Modifier
                    .secondaryItemAlpha()
                    .padding(top = 8.dp)
                    .padding(horizontal = 16.dp),
                annotator = descriptionAnnotator,
            )
            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .alpha(0.3f),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimeSummary(
            description = desc,
            expanded = expanded,
            onExpand = { onExpanded(!expanded) },
            modifier = Modifier
                .padding(horizontal = 16.dp),
        )
        val tags = tagsProvider()
        if (!tags.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .padding(vertical = 12.dp)
                    .animateContentSize(animationSpec = spring())
                    .fillMaxWidth(),
            ) {
                var showMenu by remember { mutableStateOf(false) }
                var tagSelected by remember { mutableStateOf("") }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_search)) },
                        onClick = {
                            onTagSearch(tagSelected)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_copy_to_clipboard)) },
                        onClick = {
                            onCopyTagToClipboard(tagSelected)
                            showMenu = false
                        },
                    )
                }
                if (expanded) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        tags.forEach {
                            TagsChip(
                                modifier = DefaultTagChipModifier,
                                text = it,
                                onClick = {
                                    tagSelected = it
                                    showMenu = true
                                },
                            )
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        items(
                            items = tags,
                            key = { tag -> "tag-$tag" },
                        ) { tag ->
                            TagsChip(
                                modifier = DefaultTagChipModifier,
                                text = tag,
                                onClick = {
                                    tagSelected = tag
                                    showMenu = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeAndSourceTitlesLarge(
    appBarPadding: Dp,
    anime: Anime,
    totalScore: Double?,
    sourceName: String,
    isStubSource: Boolean,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    mergedSources: ImmutableList<eu.kanade.tachiyomi.source.Source>,
    isRefreshing: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (entry, ratio) = AnimeCover.getEntry(anime.id)
        entry(
            modifier = Modifier
                .width(if (entry == AnimeCover.Panorama) 200.dp else 160.dp)
                .tvFocusHighlight(
                    shape = MaterialTheme.shapes.medium,
                    borderWidth = 2.5.dp,
                    focusedScale = 1.03f,
                ),
            data = anime.asAnimeCover(),
            contentDescription = stringResource(MR.strings.manga_cover),
            onClick = onCoverClick,
            ratio = ratio,
            shouldExtractColor = true,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AnimeContentInfo(
                title = anime.title,
                author = anime.author,
                artist = anime.artist,
                status = anime.status,
                score = totalScore,
                sourceName = sourceName,
                isStubSource = isStubSource,
                doSearch = doSearch,
                textAlign = TextAlign.Start,
                mergedSources = mergedSources,
                isRefreshing = isRefreshing,
            )
        }
    }
}

@Composable
private fun AnimeAndSourceTitlesSmall(
    appBarPadding: Dp,
    anime: Anime,
    totalScore: Double?,
    sourceName: String,
    isStubSource: Boolean,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    mergedSources: ImmutableList<eu.kanade.tachiyomi.source.Source>,
    isRefreshing: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (entry, ratio) = AnimeCover.getEntry(anime.id)
        entry(
            modifier = Modifier
                .width(if (entry == AnimeCover.Panorama) 140.dp else 100.dp)
                .tvFocusHighlight(
                    shape = MaterialTheme.shapes.medium,
                    borderWidth = 2.5.dp,
                    focusedScale = 1.03f,
                ),
            data = anime.asAnimeCover(),
            contentDescription = stringResource(MR.strings.manga_cover),
            onClick = onCoverClick,
            ratio = ratio,
            shouldExtractColor = true,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AnimeContentInfo(
                title = anime.title,
                author = anime.author,
                artist = anime.artist,
                status = anime.status,
                score = totalScore,
                sourceName = sourceName,
                isStubSource = isStubSource,
                doSearch = doSearch,
                textAlign = TextAlign.Start,
                mergedSources = mergedSources,
                isRefreshing = isRefreshing,
            )
        }
    }
}

@Composable
private fun AnimeContentInfo(
    title: String,
    author: String?,
    artist: String?,
    status: Long,
    score: Double?,
    sourceName: String,
    isStubSource: Boolean,
    doSearch: (query: String, global: Boolean) -> Unit,
    textAlign: TextAlign? = LocalTextStyle.current.textAlign,
    mergedSources: ImmutableList<eu.kanade.tachiyomi.source.Source>,
    isRefreshing: Boolean,
) {
    val context = LocalContext.current
    Text(
        text = title.ifBlank { stringResource(MR.strings.unknown_title) },
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .tvFocusHighlight(
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1.02f,
            )
            .clickableNoIndication(
                onLongClick = {
                    if (title.isNotBlank()) {
                        context.copyToClipboard(
                            title,
                            title,
                        )
                    }
                },
                onClick = { if (title.isNotBlank()) doSearch(title, true) },
            ),
        textAlign = textAlign,
    )

    Row(
        modifier = Modifier
            .secondaryItemAlpha()
            .tvFocusHighlight(
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1.02f,
            ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PersonOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = author?.takeIf { it.isNotBlank() }
                ?: if (isRefreshing) "" else stringResource(MR.strings.unknown_author),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickableNoIndication(
                    onLongClick = {
                        if (!author.isNullOrBlank()) {
                            context.copyToClipboard(
                                author,
                                author,
                            )
                        }
                    },
                    onClick = { if (!author.isNullOrBlank()) doSearch(author, true) },
                ),
            textAlign = textAlign,
        )
    }

    if (!artist.isNullOrBlank() && author != artist) {
        Row(
            modifier = Modifier
                .secondaryItemAlpha()
                .tvFocusHighlight(
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.02f,
                ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Brush,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickableNoIndication(
                        onLongClick = { context.copyToClipboard(artist, artist) },
                        onClick = { doSearch(artist, true) },
                    ),
                textAlign = textAlign,
            )
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (textAlign == TextAlign.Center) Arrangement.Center else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoChip(
            icon = when (status) {
                SAnime.ONGOING.toLong() -> Icons.Outlined.Schedule
                SAnime.COMPLETED.toLong() -> Icons.Outlined.DoneAll
                SAnime.LICENSED.toLong() -> Icons.Outlined.AttachMoney
                SAnime.PUBLISHING_FINISHED.toLong() -> Icons.Outlined.Done
                SAnime.CANCELLED.toLong() -> Icons.Outlined.Close
                SAnime.ON_HIATUS.toLong() -> Icons.Outlined.Pause
                SAnime.UPCOMING.toLong() -> Icons.Filled.HourglassEmpty
                else -> Icons.Outlined.Block
            },
            text = when (status) {
                SAnime.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
                SAnime.COMPLETED.toLong() -> stringResource(MR.strings.completed)
                SAnime.LICENSED.toLong() -> stringResource(MR.strings.licensed)
                SAnime.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
                SAnime.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
                SAnime.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
                SAnime.UPCOMING.toLong() -> stringResource(MR.strings.upcoming)
                else -> stringResource(MR.strings.unknown)
            },
        )
        
        val scoreText = remember(score) {
            if (score != null && score > 0) {
                String.format("%.1f", score)
            } else null
        }
        if (scoreText != null) {
            Spacer(modifier = Modifier.width(4.dp))
            InfoChip(
                icon = Icons.Default.Star,
                text = scoreText,
                iconTint = Color(0xFFFFD700) // Gold
            )
        }

        Spacer(modifier = Modifier.width(4.dp))
        var isRevealed by remember { mutableStateOf(false) }
        val displayText = if (isRevealed) sourceName
        else if (isStubSource) sourceName
        else if (sourceName.contains("Local")) stringResource(MR.strings.local_source)
        else "Global"

        InfoChip(
            icon = if (isStubSource) Icons.Filled.Warning else if (sourceName.contains("Local")) Icons.Outlined.DoneAll else Icons.Outlined.Public,
            text = displayText,
            iconTint = if (isStubSource) MaterialTheme.colorScheme.error else if (isRevealed) MaterialTheme.colorScheme.primary else null,
            onClick = { isRevealed = !isRevealed }
        )

        MergedSourcesInfo(mergedSources)
    }
}

@Composable
private fun MergedSourcesInfo(
    mergedSources: ImmutableList<eu.kanade.tachiyomi.source.Source>,
) {
    mergedSources.forEach { source ->
        Spacer(modifier = Modifier.width(4.dp))
        InfoChip(
            icon = Icons.Outlined.Language,
            text = source.getNameForAnimeInfo(),
        )
    }
}

@Composable
private fun InfoChip(
    icon: ImageVector,
    text: String,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickableNoIndication(onClick = onClick ?: {})
            .animateContentSize(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = iconTint ?: MaterialTheme.colorScheme.primary
            )
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.9f))
                        .togetherWith(fadeOut())
                },
                label = "chipText"
            ) { targetText ->
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val descriptionAnnotator = markdownAnnotator(
    annotate = { content, child ->
        if (child.type in DISALLOWED_MARKDOWN_TYPES) {
            append(content.substring(child.startOffset, child.endOffset))
            return@markdownAnnotator true
        }

        false
    },
    config = markdownAnnotatorConfig(
        eolAsNewLine = true,
    ),
)

@Composable
private fun AnimeSummary(
    description: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .tvFocusHighlight(
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.01f,
            )
            .clipToBounds()
            .clickableNoIndication(onClick = onExpand),
    ) {
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!expanded) Modifier.height(80.dp) else Modifier),
            ) {
                MarkdownRender(
                    content = description,
                    modifier = Modifier
                        .secondaryItemAlpha()
                        .padding(bottom = if (expanded) 24.dp else 0.dp)
                        .drawWithContent {
                            drawContent()
                            if (!expanded) {
                                val gradientHeight = 24.dp.toPx()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            backgroundColor,
                                        ),
                                        startY = size.height - gradientHeight,
                                        endY = size.height,
                                    ),
                                )
                            }
                        },
                    annotator = descriptionAnnotator,
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    if (!expanded) {
                        Modifier.background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    backgroundColor.copy(alpha = 0.8f),
                                ),
                            ),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_caret_down)
            Icon(
                painter = rememberAnimatedVectorPainter(image, !expanded),
                contentDescription = stringResource(
                    if (expanded) MR.strings.manga_info_collapse else MR.strings.manga_info_expand,
                ),
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private val DefaultTagChipModifier = Modifier.padding(vertical = 4.dp)

@Composable
private fun TagsChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        androidx.compose.material3.SuggestionChip(
            modifier = modifier.tvFocusHighlight(
                shape = androidx.compose.material3.SuggestionChipDefaults.shape,
                focusedScale = 1.05f,
            ),
            onClick = onClick,
            label = { Text(text = text, style = MaterialTheme.typography.bodySmall) },
            colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = null,
        )
    }
}

@Composable
private fun RowScope.AnimeActionButton(
    title: String,
    icon: ImageVector? = null,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    iconContent: (@Composable () -> Unit)? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .tvFocusHighlight(
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.05f,
            ),
        onLongClick = onLongClick,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (iconContent != null) {
                iconContent()
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                color = color,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}