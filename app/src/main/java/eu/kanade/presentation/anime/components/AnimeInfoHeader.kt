package eu.kanade.presentation.anime.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Button
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import tachiyomi.domain.anime.model.asAnimeBackground
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
    // ANZ -->
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Immersive backdrop: the artwork fades into the page background.
        val backgroundColor = MaterialTheme.colorScheme.background
        val isDarkPage = backgroundColor.luminance() < 0.5f
        val context = LocalContext.current
        val uiPreferences = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
        val animatedTransitions by uiPreferences.animatedTransitions().collectAsState()
        val hasSourceBackdrop = !anime.backgroundUrl.isNullOrEmpty()
        val backdropImageRequest = remember(
            anime.id,
            anime.thumbnailUrl,
            anime.coverLastModified,
            anime.backgroundUrl,
            anime.backgroundLastModified,
            animatedTransitions,
        ) {
            ImageRequest.Builder(context)
                // Prefer the source-provided background image; fall back to the cover.
                .data(
                    if (!anime.backgroundUrl.isNullOrEmpty()) {
                        anime.asAnimeBackground()
                    } else {
                        anime.asAnimeCover()
                    },
                )
                .precision(coil3.size.Precision.INEXACT)
                .crossfade(animatedTransitions)
                .build()
        }
        AsyncImage(
            model = backdropImageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = if (isDarkPage) 0.9f else 0.6f,
            modifier = Modifier
                .matchParentSize()
                .clipToBounds()
                .graphicsLayer {
                    // Slight zoom hides the soft edges the blur leaves around the image.
                    scaleX = 1.15f
                    scaleY = 1.15f
                }
                .blur(if (hasSourceBackdrop) 14.dp else 28.dp),
        )
        val deepColor = if (isDarkPage) Color.Black else backgroundColor
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to deepColor.copy(alpha = 0.50f),
                        0.45f to deepColor.copy(alpha = 0.22f),
                        0.85f to backgroundColor.copy(alpha = 0.92f),
                        1f to backgroundColor,
                    ),
                ),
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
    // ANZ <--
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
    showContinueButton: Boolean = false,
    modifier: Modifier = Modifier,
) {
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

    // ANZ -->
    Column(
        modifier = modifier.padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showContinueButton) {
            Button(
                onClick = onContinueWatching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(
                        elevation = 14.dp,
                        shape = RoundedCornerShape(18.dp),
                        ambientColor = MaterialTheme.colorScheme.primary,
                        spotColor = MaterialTheme.colorScheme.primary,
                    )
                    .tvFocusHighlight(
                        shape = RoundedCornerShape(18.dp),
                        focusedScale = 1.03f,
                    ),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(if (isWatching) MR.strings.action_resume else MR.strings.action_start),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }

        // One quiet bar that groups every quick action.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimeActionButton(
                title = if (favorite) stringResource(MR.strings.in_library) else stringResource(MR.strings.add_to_library),
                icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                active = favorite,
                onClick = onAddToLibraryClicked,
                onLongClick = onEditCategory,
            )
            // "Next update" makes no sense for a finished show, so the tile is hidden then.
            if (status != SAnime.COMPLETED.toLong()) {
                AnimeActionButton(
                    title = if (fetchInterval == tachiyomi.domain.anime.interactor.FetchInterval.MANUAL_DISABLE) {
                        stringResource(MR.strings.disabled)
                    } else {
                        nextUpdateCountdown ?: when (nextUpdateDays) {
                            null -> stringResource(MR.strings.not_applicable)
                            0 -> stringResource(MR.strings.manga_interval_expected_update_soon)
                            else -> pluralStringResource(
                                MR.plurals.day,
                                count = nextUpdateDays,
                                nextUpdateDays,
                            )
                        }
                    },
                    icon = if (fetchInterval == tachiyomi.domain.anime.interactor.FetchInterval.MANUAL_DISABLE) {
                        Icons.Outlined.HourglassDisabled
                    } else {
                        Icons.Default.HourglassEmpty
                    },
                    active = isUserIntervalMode,
                    showDivider = true,
                    onClick = { if (favorite) onEditIntervalClicked?.invoke() },
                )
            }
            AnimeActionButton(
                title = run {
                    if (mainTrackItem?.track != null) {
                        val trackStatus = (mainTrackItem.tracker as? eu.kanade.tachiyomi.data.track.AnimeTracker)?.getStatusForAnime(mainTrackItem.track.status)
                        val statusText = trackStatus?.let { stringResource(it) } ?: ""
                        statusText.ifBlank { stringResource(MR.strings.manga_tracking_tab) }
                    } else {
                        stringResource(MR.strings.manga_tracking_tab)
                    }
                },
                icon = if (mainTrackItem?.track != null || trackingCount > 0) {
                    Icons.Outlined.Done
                } else {
                    Icons.Outlined.Sync
                },
                active = trackingCount > 0 || mainTrackItem?.track != null,
                showDivider = true,
                onClick = onTrackingClicked,
            )
            if (onWebViewClicked != null) {
                AnimeActionButton(
                    title = stringResource(MR.strings.action_web_view),
                    icon = Icons.Outlined.Public,
                    active = false,
                    showDivider = true,
                    onClick = onWebViewClicked,
                    onLongClick = onWebViewLongClicked,
                )
            }
        }
    }
    // ANZ <--
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    // ANZ -->
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = appBarPadding + 16.dp, end = 24.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (entry, ratio) = AnimeCover.getEntry(anime.id)
        val coverShape = RoundedCornerShape(20.dp)
        val glowColor = MaterialTheme.colorScheme.primary
        entry(
            modifier = Modifier
                .width(if (entry == AnimeCover.Panorama) 240.dp else 180.dp)
                .shadow(elevation = 24.dp, shape = coverShape, ambientColor = glowColor, spotColor = glowColor)
                .tvFocusHighlight(
                    shape = coverShape,
                    borderWidth = 2.5.dp,
                    focusedScale = 1.03f,
                ),
            data = anime.asAnimeCover(),
            contentDescription = stringResource(MR.strings.manga_cover),
            shape = coverShape,
            onClick = onCoverClick,
            ratio = ratio,
            shouldExtractColor = true,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
    // ANZ <--
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
    // ANZ -->
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = appBarPadding + 12.dp, end = 20.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val (entry, ratio) = AnimeCover.getEntry(anime.id)
        val coverShape = RoundedCornerShape(26.dp)
        val glowColor = MaterialTheme.colorScheme.primary
        val screenWidth = LocalConfiguration.current.screenWidthDp
        val coverWidth = if (entry == AnimeCover.Panorama) {
            (screenWidth - 40).coerceIn(240, 440).dp
        } else {
            (screenWidth * 0.72f).coerceIn(200f, 330f).dp
        }
        val appear = remember(anime.id) { Animatable(0f) }
        LaunchedEffect(anime.id) {
            appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 200f))
        }
        // Soft aura in the cover's colour behind the poster, so it feels lit from within.
        Box(
            modifier = Modifier.drawBehind {
                val radius = size.maxDimension * 0.85f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = 0.38f), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            entry(
                modifier = Modifier
                    .width(coverWidth)
                    .graphicsLayer {
                        val grow = 0.9f + 0.1f * appear.value
                        scaleX = grow
                        scaleY = grow
                        alpha = appear.value.coerceIn(0f, 1f)
                    }
                    .shadow(elevation = 32.dp, shape = coverShape, ambientColor = glowColor, spotColor = glowColor)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), coverShape)
                    .tvFocusHighlight(
                        shape = coverShape,
                        borderWidth = 2.5.dp,
                        focusedScale = 1.03f,
                    ),
                data = anime.asAnimeCover(),
                contentDescription = stringResource(MR.strings.manga_cover),
                shape = coverShape,
                onClick = onCoverClick,
                ratio = ratio,
                shouldExtractColor = true,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        AnimeContentInfo(
            title = anime.title,
            author = anime.author,
            artist = anime.artist,
            status = anime.status,
            score = totalScore,
            sourceName = sourceName,
            isStubSource = isStubSource,
            doSearch = doSearch,
            textAlign = TextAlign.Center,
            mergedSources = mergedSources,
            isRefreshing = isRefreshing,
        )
    }
    // ANZ <--
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
    // ANZ -->
    val context = LocalContext.current
    val centered = textAlign == TextAlign.Center

    Text(
        text = title.ifBlank { stringResource(MR.strings.unknown_title) },
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
            lineHeight = 30.sp,
        ),
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
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

    // One quiet credit line; nothing at all when the source gives no author.
    val placeholders = setOf("desconocido", "unknown", "n/a", "-", "none")
    val authorName = author?.takeIf { it.isNotBlank() && it.trim().lowercase() !in placeholders }
    val artistName = artist?.takeIf {
        it.isNotBlank() && it != author && it.trim().lowercase() !in placeholders
    }
    val creditLine = listOfNotNull(authorName, artistName).joinToString("  •  ")
    if (creditLine.isNotEmpty()) {
        Text(
            text = creditLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .tvFocusHighlight(
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.02f,
                )
                .clickableNoIndication(
                    onLongClick = { context.copyToClipboard(creditLine, creditLine) },
                    onClick = { (authorName ?: artistName)?.let { doSearch(it, true) } },
                ),
            textAlign = textAlign,
        )
    }

    Spacer(modifier = Modifier.height(2.dp))

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            6.dp,
            if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
            } else {
                null
            }
        }
        if (scoreText != null) {
            InfoChip(
                icon = Icons.Default.Star,
                text = scoreText,
                iconTint = Color(0xFFFFD700), // Gold
            )
        }

        var isRevealed by remember { mutableStateOf(false) }
        val displayText = if (isRevealed) {
            sourceName
        } else if (isStubSource) {
            sourceName
        } else if (sourceName.contains("Local")) {
            stringResource(MR.strings.local_source)
        } else {
            "Global"
        }

        InfoChip(
            icon = if (isStubSource) Icons.Filled.Warning else if (sourceName.contains("Local")) Icons.Outlined.DoneAll else Icons.Outlined.Public,
            text = displayText,
            iconTint = if (isStubSource) MaterialTheme.colorScheme.error else if (isRevealed) MaterialTheme.colorScheme.primary else null,
            onClick = { isRevealed = !isRevealed },
        )

        MergedSourcesInfo(mergedSources)
    }
    // ANZ <--
}

@Composable
private fun MergedSourcesInfo(
    mergedSources: ImmutableList<eu.kanade.tachiyomi.source.Source>,
) {
    mergedSources.forEach { source ->
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
    // ANZ -->
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickableNoIndication(onClick = onClick ?: {})
            .animateContentSize(),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = iconTint ?: MaterialTheme.colorScheme.primary,
            )
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.9f))
                        .togetherWith(fadeOut())
                },
                label = "chipText",
            ) { targetText ->
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // ANZ <--
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
    // ANZ -->
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .tvFocusHighlight(
                shape = shape,
                focusedScale = 1.01f,
            )
            .clip(shape)
            .background(cardColor)
            .clickableNoIndication(onClick = onExpand),
    ) {
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!expanded) Modifier.height(124.dp) else Modifier)
                    .clipToBounds(),
            ) {
                MarkdownRender(
                    content = description,
                    modifier = Modifier
                        .secondaryItemAlpha()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 14.dp,
                            bottom = if (expanded) 40.dp else 0.dp,
                        ),
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
                                    cardColor,
                                ),
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(top = 28.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_caret_down)
            Icon(
                painter = rememberAnimatedVectorPainter(image, !expanded),
                contentDescription = stringResource(
                    if (expanded) MR.strings.manga_info_collapse else MR.strings.manga_info_expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
    // ANZ <--
}

private val DefaultTagChipModifier = Modifier.padding(vertical = 4.dp)

// ANZ -->
@Composable
private fun TagsChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier
            .tvFocusHighlight(
                shape = shape,
                focusedScale = 1.05f,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Compact action inside the shared bar: icon over a single-line label, tinted when active. */
@Composable
private fun RowScope.AnimeActionButton(
    title: String,
    icon: ImageVector? = null,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    iconContent: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (active) colors.primary else colors.onSurfaceVariant
    val dividerColor = colors.onSurface.copy(alpha = 0.14f)
    Column(
        modifier = Modifier
            .weight(1f)
            .tvFocusHighlight(
                shape = RoundedCornerShape(20.dp),
                focusedScale = 1.05f,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .drawBehind {
                if (showDivider) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, size.height * 0.28f),
                        end = Offset(0f, size.height * 0.72f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (iconContent != null) {
            iconContent()
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
// ANZ <--
