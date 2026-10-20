package eu.kanade.presentation.anime.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.DownloadAction
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.components.UpIcon
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

// ANZ -->
/**
 * Lets the transparent toolbar follow the page: it slides away when the list scrolls down and comes
 * straight back when it scrolls up. It only listens to the scroll, it never consumes it.
 */
@Stable
class ToolbarSlide {
    /** Toolbar height in px, reported by the toolbar itself. */
    var height by mutableFloatStateOf(0f)

    /** 0 = fully shown, -height = fully hidden. */
    var offset by mutableFloatStateOf(0f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            offset = (offset + available.y).coerceIn(-height, 0f)
            return Offset.Zero
        }
    }

    fun show() {
        offset = 0f
    }
}

/** Remembers a [ToolbarSlide] and shows the bar again whenever the list is back at the very top. */
@Composable
fun rememberToolbarSlide(listState: LazyListState): ToolbarSlide {
    val slide = androidx.compose.runtime.remember { ToolbarSlide() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { atTop -> if (atTop) slide.show() }
    }
    return slide
}
// ANZ <--

@Composable
fun AnimeToolbar(
    title: String,
    titleAlphaProvider: () -> Float,
    hasFilters: Boolean,
    onBackClicked: () -> Unit,
    onClickFilter: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickDownload: ((DownloadAction) -> Unit)?,
    onClickEditCategory: (() -> Unit)?,
    onClickRefresh: () -> Unit,
    onClickMigrate: (() -> Unit)?,
    onClickEditNotes: () -> Unit,
    onClickSettings: (() -> Unit)?,
    onClickSuggestions: (() -> Unit)?,
    // SY -->
    onClickEditInfo: (() -> Unit)?,
    onClickClearAnime: (() -> Unit)?,
    onClickOpenAnimeFolder: (() -> Unit)?,
    onClickMerge: (() -> Unit)?,
    // SY <--
    // Anime only
    changeAnimeSkipIntro: (() -> Unit)?,
    // For action mode
    actionModeCounter: Int,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundAlphaProvider: () -> Float = titleAlphaProvider,
    // ANZ -->
    slide: ToolbarSlide? = null,
    // ANZ <--
) {
    val statusBarPadding = WindowInsets.systemBars.only(WindowInsetsSides.Top).asPaddingValues()
    val topPadding = statusBarPadding.calculateTopPadding()
    var stableTopPadding by remember { mutableStateOf(0.dp) }
    if (topPadding > 0.dp && stableTopPadding == 0.dp) {
        stableTopPadding = topPadding
    }

    // ANZ -->
    // No solid bar any more: the page colour only fades in softly behind the icons once content is
    // sliding under them (alpha is read in the draw phase, so nothing recomposes per frame). While
    // episodes are selected the bar is solid again so the counter and the actions read clearly.
    val isActionMode = actionModeCounter > 0
    val fadeColor = MaterialTheme.colorScheme.background
    val solidColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    Column(
        modifier = modifier
            .onSizeChanged { slide?.height = it.height.toFloat() }
            .graphicsLayer {
                if (slide != null && !isActionMode) {
                    val offset = slide.offset.coerceIn(-slide.height, 0f)
                    translationY = offset
                    alpha = if (slide.height > 0f) (1f + offset / slide.height).coerceIn(0f, 1f) else 1f
                }
            }
            .drawBehind {
                if (isActionMode) {
                    drawRect(color = solidColor)
                } else {
                    val strength = backgroundAlphaProvider().coerceIn(0f, 1f) * 0.9f
                    if (strength > 0.01f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    fadeColor.copy(alpha = strength),
                                    fadeColor.copy(alpha = strength * 0.6f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                    }
                }
            }
            .padding(top = stableTopPadding),
    ) {
    // ANZ <--
        TopAppBar(
            title = {
                Text(
                    text = if (isActionMode) actionModeCounter.toString() else title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        alpha = if (isActionMode) 1f else titleAlphaProvider()
                    },
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClicked) {
                    UpIcon(navigationIcon = Icons.Outlined.Close.takeIf { isActionMode })
                }
            },
            actions = {
                if (isActionMode) {
                    AppBarActions(
                        persistentListOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = onSelectAll,
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = onInvertSelection,
                            ),
                        ),
                    )
                } else {
                    var downloadExpanded by remember { mutableStateOf(false) }
                    if (onClickDownload != null) {
                        val onDismissRequest = { downloadExpanded = false }
                        DownloadDropdownMenu(
                            expanded = downloadExpanded,
                            onDismissRequest = onDismissRequest,
                            onDownloadClicked = onClickDownload,
                        )
                    }

                    // ANZ --> theme color instead of the yellow "active" tint
                    val filterTint = if (hasFilters) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    // ANZ <--
                    AppBarActions(
                        actions = persistentListOf<AppBar.AppBarAction>().builder()
                            .apply {
                                if (onClickDownload != null) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.manga_download),
                                            icon = Icons.Outlined.Download,
                                            onClick = { downloadExpanded = !downloadExpanded },
                                        ),
                                    )
                                }
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_filter),
                                        icon = Icons.Outlined.FilterList,
                                        iconTint = filterTint,
                                        onClick = onClickFilter,
                                    ),
                                )
                                if (onClickRefresh != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_webview_refresh),
                                            onClick = onClickRefresh,
                                        ),
                                    )
                                }
                                if (onClickEditCategory != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_edit_categories),
                                            onClick = onClickEditCategory,
                                        ),
                                    )
                                }
                                if (onClickMigrate != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_migrate),
                                            onClick = onClickMigrate,
                                        ),
                                    )
                                }
                                if (onClickShare != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_share),
                                            onClick = onClickShare,
                                        ),
                                    )
                                }
                                add(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_edit_notes),
                                        onClick = onClickEditNotes,
                                    ),
                                )
                                // SY -->
                                if (onClickMerge != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.merge_settings),
                                            onClick = onClickMerge,
                                        ),
                                    )
                                }
                                if (onClickEditInfo != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_edit_info),
                                            onClick = onClickEditInfo,
                                        ),
                                    )
                                }
                                if (onClickOpenAnimeFolder != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(KMR.strings.action_open_folder),
                                            onClick = onClickOpenAnimeFolder,
                                        ),
                                    )
                                }
                                if (onClickClearAnime != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_clear_anime),
                                            onClick = onClickClearAnime,
                                        ),
                                    )
                                }
                                if (onClickSuggestions != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(tachiyomi.i18n.sy.SYMR.strings.az_recommends),
                                            onClick = onClickSuggestions,
                                        ),
                                    )
                                }
                                if (onClickSettings != null) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.settings),
                                            onClick = onClickSettings,
                                        ),
                                    )
                                }
                                // SY <--
                            }
                            .build(),
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
        )
    }
}
