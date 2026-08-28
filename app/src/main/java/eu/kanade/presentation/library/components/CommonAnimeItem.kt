package eu.kanade.presentation.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.presentation.browse.components.SourceIcon
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.domain.ui.UiPreferences
import androidx.compose.runtime.collectAsState
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.presentation.core.util.tvFocusHighlight
import tachiyomi.presentation.core.util.tvListItemFocusHighlight
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.anime.model.AnimeCover as EntryCoverModel

object CommonAnimeItemDefaults {
    val GridHorizontalSpacer = 4.dp
    val GridVerticalSpacer = 4.dp

    @Suppress("ConstPropertyName")
    const val BrowseFavoriteCoverAlpha = 0.34f
}

private val ContinueWatchingButtonSizeSmall = 28.dp
private val ContinueWatchingButtonSizeLarge = 32.dp

private val ContinueWatchingButtonIconSizeSmall = 16.dp
private val ContinueWatchingButtonIconSizeLarge = 20.dp

private val ContinueWatchingButtonGridPadding = 6.dp
private val ContinueWatchingButtonListSpacing = 8.dp

private const val GRID_SELECTED_COVER_ALPHA = 0.76f

/**
 * Layout of grid list item with title overlaying the cover.
 * Accepts null [title] for a cover-only view.
 */
@Composable
fun AnimeCompactGridItem(
    coverData: EntryCoverModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    title: String? = null,
    onClickContinueWatching: (() -> Unit)? = null,
    coverAlpha: Float = 1f,
    coverBadgeStart: @Composable (RowScope.() -> Unit)? = null,
    coverBadgeEnd: @Composable (RowScope.() -> Unit)? = null,
    usePanorama: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    GridItemSelectable(
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    ) {
        val (entry, ratio) = AnimeCover.getEntry(coverData.animeId, usePanoramaOverride = usePanorama)
        val coverBlock: @Composable BoxScope.() -> Unit = remember(entry, coverData, ratio, isSelected, coverAlpha) {
            {
                entry(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.alpha = if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha
                            this.compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    data = coverData,
                    ratio = ratio,
                    shape = RectangleShape, // Optimization: Parent clips
                    shouldExtractColor = true,
                )
            }
        }
        val contentBlock: @Composable BoxScope.() -> Unit = remember(title, onClickContinueWatching) {
            {
                if (title != null) {
                    CoverTextOverlay(
                        title = title,
                        onClickContinueWatching = onClickContinueWatching,
                    )
                } else if (onClickContinueWatching != null) {
                    ContinueWatchingButton(
                        size = ContinueWatchingButtonSizeLarge,
                        iconSize = ContinueWatchingButtonIconSizeLarge,
                        onClick = onClickContinueWatching,
                        modifier = Modifier
                            .padding(ContinueWatchingButtonGridPadding)
                            .align(Alignment.BottomEnd),
                    )
                }
            }
        }
        AnimeGridCover(
            cover = coverBlock,
            ratio = ratio,
            badgesStart = coverBadgeStart,
            badgesEnd = coverBadgeEnd,
            content = contentBlock,
        )
    }
}

/**
 * Title overlay for [AnimeCompactGridItem]
 */
@Composable
internal fun BoxScope.CoverTextOverlay(
    title: String,
    onClickContinueWatching: (() -> Unit)?,
) {
    val gradient = remember {
        Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color(0xCC000000),
        )
    }
    Spacer(
        modifier = Modifier
            .fillMaxHeight(0.33f)
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .drawBehind {
                drawRect(brush = gradient)
            },
    )
    Row(
        modifier = Modifier.align(Alignment.BottomStart),
        verticalAlignment = Alignment.Bottom,
    ) {
        GridItemTitle(
            modifier = Modifier
                .weight(1f)
                .padding(all = 4.dp),
            title = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = Color.White,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.8f),
                    offset = Offset(0f, 2f),
                    blurRadius = 4f,
                ),
            ),
            minLines = 1,
            maxLines = 2,
        )
        if (onClickContinueWatching != null) {
            ContinueWatchingButton(
                size = ContinueWatchingButtonSizeSmall,
                iconSize = ContinueWatchingButtonIconSizeSmall,
                onClick = onClickContinueWatching,
                modifier = Modifier.padding(
                    bottom = ContinueWatchingButtonGridPadding,
                    end = ContinueWatchingButtonGridPadding,
                ),
            )
        }
    }
}

/**
 * Layout of grid list item with title below the cover.
 */
@Composable
fun AnimeComfortableGridItem(
    coverData: EntryCoverModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    title: String = "",
    titleMaxLines: Int = 2,
    coverAlpha: Float = 1f,
    onClickContinueWatching: (() -> Unit)? = null,
    coverBadgeStart: @Composable (RowScope.() -> Unit)? = null,
    coverBadgeEnd: @Composable (RowScope.() -> Unit)? = null,
    usePanorama: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    GridItemSelectable(
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    ) {
        val (entry, ratio) = AnimeCover.getEntry(coverData.animeId, usePanoramaOverride = usePanorama)
        val coverBlock: @Composable BoxScope.() -> Unit = remember(entry, coverData, ratio, isSelected, coverAlpha) {
            {
                entry(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.alpha = if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha
                            this.compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    data = coverData,
                    ratio = ratio,
                    shape = RectangleShape, // Optimization: Parent clips
                    shouldExtractColor = true,
                )
            }
        }
        val contentBlock: @Composable BoxScope.() -> Unit = remember(onClickContinueWatching) {
            {
                if (onClickContinueWatching != null) {
                    ContinueWatchingButton(
                        size = ContinueWatchingButtonSizeLarge,
                        iconSize = ContinueWatchingButtonIconSizeLarge,
                        onClick = onClickContinueWatching,
                        modifier = Modifier
                            .padding(ContinueWatchingButtonGridPadding)
                            .align(Alignment.BottomEnd),
                    )
                }
            }
        }
        Column {
            AnimeGridCover(
                cover = coverBlock,
                ratio = ratio,
                badgesStart = coverBadgeStart,
                badgesEnd = coverBadgeEnd,
                content = contentBlock,
            )
            GridItemTitle(
                modifier = Modifier.padding(4.dp),
                title = title,
                style = MaterialTheme.typography.titleSmall,
                minLines = 2,
                maxLines = titleMaxLines,
            )
        }
    }
}

/**
 * Common cover layout to add contents to be drawn on top of the cover.
 */
@Composable
private fun AnimeGridCover(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    ratio: Float = AnimeCover.Book.ratio,
    cover: @Composable BoxScope.() -> Unit = {},
    badgesStart: (@Composable RowScope.() -> Unit)? = null,
    badgesEnd: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable (BoxScope.() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .graphicsLayer {
                this.shape = shape
                this.clip = true
            },
    ) {
        cover()
        content?.invoke(this)
        if (badgesStart != null) {
            BadgeGroup(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.TopStart),
                content = badgesStart,
            )
        }

        if (badgesEnd != null) {
            BadgeGroup(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.TopEnd),
                content = badgesEnd,
            )
        }
    }
}

@Composable
internal fun GridItemTitle(
    title: String,
    style: TextStyle,
    minLines: Int,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    Text(
        modifier = modifier,
        text = title,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}

/**
 * Wrapper for grid items to handle selection state, click and long click.
 */
@Composable
internal fun GridItemSelectable(
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale = if (isSelected) 0.95f else 1f
    val shape = MaterialTheme.shapes.medium
    val borderColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .tvFocusHighlight(
                shape = shape,
                focusedScale = 1.04f,
                borderWidth = 2.5.dp,
            )
            .then(
                if (scale < 1f) {
                    Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.shape = shape
                        this.clip = true
                    }
                } else {
                    Modifier.clip(shape)
                },
            )
            .drawBehind {
                if (isSelected) {
                    drawRoundRect(
                        color = borderColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    )
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(4.dp),
    ) {
        val contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            LocalContentColor.current
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .drawBehind {
                        drawCircle(color = surfaceColor)
                    },
            )
        }
    }
}

/**
 * Layout of list item.
 */
@Composable
fun AnimeListItem(
    isSelected: Boolean = false,
    title: String,
    coverData: EntryCoverModel,
    coverAlpha: Float = 1f,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    badge: @Composable (RowScope.() -> Unit),
    onClickContinueWatching: (() -> Unit)? = null,
    entries: Int = 0,
    containerHeight: Int = 0,
    usePanorama: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val height = remember(usePanorama) {
        if (usePanorama == true) 96.dp else 76.dp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvListItemFocusHighlight()
            .then(modifier)
            .selectedBackground(isSelected)
            .height(height)
            .clickable(
                onClick = {
                    onClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (entry, ratio) = AnimeCover.getEntry(
            coverData.animeId,
            usePanoramaOverride = usePanorama,
        )
        entry(
            modifier = Modifier
                .fillMaxHeight()
                .graphicsLayer {
                    alpha = coverAlpha
                    this.compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            data = coverData,
            ratio = ratio,
        )
        Text(
            text = title,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        BadgeGroup(content = badge)
        if (onClickContinueWatching != null) {
            ContinueWatchingButton(
                size = ContinueWatchingButtonSizeSmall,
                iconSize = ContinueWatchingButtonIconSizeSmall,
                onClick = onClickContinueWatching,
                modifier = Modifier.padding(start = ContinueWatchingButtonListSpacing),
            )
        }
    }
}

@Composable
private fun ContinueWatchingButton(
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FilledIconButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
            ),
            modifier = Modifier.size(size),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(MR.strings.action_resume),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
