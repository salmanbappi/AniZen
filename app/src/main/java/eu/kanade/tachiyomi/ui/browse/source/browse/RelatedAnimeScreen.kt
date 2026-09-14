package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGridItem
import eu.kanade.presentation.browse.components.BrowseSourceCompactGridItem
import eu.kanade.presentation.browse.components.BrowseSourceListItem
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.library.components.CommonAnimeItemDefaults
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref

class RelatedAnimeScreen(val animeId: Long) : Screen() {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { RelatedAnimeScreenModel(animeId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val showHome by sourcePreferences.relatedAnimeShowHome().changes()
            .collectAsState(sourcePreferences.relatedAnimeShowHome().get())

        val navigateUp: () -> Unit = {
            if (state.selectionMode) {
                screenModel.clearSelection()
            } else {
                navigator.pop()
            }
        }

        BackHandler(enabled = state.selectionMode, onBack = navigateUp)

        Scaffold(
            topBar = { scrollBehavior ->
                BrowseSourceToolbar(
                    searchQuery = null,
                    onSearchQueryChange = {},
                    source = remember { StubSource(id = -1L, lang = "", name = "Discover Related") },
                    subtitle = state.title,
                    displayMode = screenModel.displayMode,
                    onDisplayModeChange = { screenModel.displayMode = it },
                    navigateUp = navigateUp,
                    onWebViewClick = {},
                    onHelpClick = {},
                    onSettingsClick = {},
                    onSearch = {},
                    scrollBehavior = scrollBehavior,
                    selectedCount = state.selection.size,
                    onUnselectAll = screenModel::clearSelection,
                    onSelectAll = screenModel::selectAll,
                    onInvertSelection = screenModel::invertSelection,
                    searchEnabled = false,
                    showMore = false,
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = state.selectionMode,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    val allFavorite = remember(state.selection, state.favoriteIds) {
                        state.selection.all { it.id in state.favoriteIds }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.large.copy(
                            bottomEnd = androidx.compose.foundation.shape.ZeroCornerSize,
                            bottomStart = androidx.compose.foundation.shape.ZeroCornerSize,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(
                                    WindowInsets.navigationBars
                                        .only(WindowInsetsSides.Bottom)
                                        .asPaddingValues(),
                                )
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            TextButton(
                                onClick = {
                                    if (allFavorite) {
                                        screenModel.removeSelectionFromLibrary()
                                    } else {
                                        screenModel.addSelectionToLibrary()
                                    }
                                },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = if (allFavorite) Icons.Outlined.Delete else Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        tint = if (allFavorite) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                    )
                                    Text(
                                        text = stringResource(
                                            if (allFavorite) MR.strings.action_remove else MR.strings.add_to_library,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (allFavorite) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { paddingValues ->
            RelatedAnimeContent(
                state = state,
                displayMode = screenModel.displayMode,
                contentPadding = paddingValues,
                onAnimeClick = { anime ->
                    if (state.selectionMode) {
                        screenModel.toggleSelection(anime)
                    } else {
                        navigator.push(AnimeScreen(anime.id))
                    }
                },
                onAnimeLongClick = screenModel::toggleSelection,
            )
        }
    }

    @Composable
    private fun RelatedAnimeContent(
        state: RelatedAnimeScreenModel.State,
        displayMode: LibraryDisplayMode,
        contentPadding: PaddingValues,
        onAnimeClick: (tachiyomi.domain.anime.model.Anime) -> Unit,
        onAnimeLongClick: (tachiyomi.domain.anime.model.Anime) -> Unit,
    ) {
        val orientation = LocalConfiguration.current.orientation
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val uiPreferences = remember { Injekt.get<UiPreferences>() }

        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

        val columns = if (displayMode == LibraryDisplayMode.List) {
            GridCells.Fixed(1)
        } else {
            val columnsCount = (if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns()).get()
            if (columnsCount == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columnsCount)
        }

        val globalPanorama by uiPreferences.panoramaCover().collectAsStatePref()
        val browseMode by uiPreferences.browsePanoramaMode().collectAsStatePref()
        val usePanorama = remember(globalPanorama, browseMode) { browseMode.resolve(globalPanorama) }

        val selectionIds = remember(state.selection) { state.selection.map { it.id }.toSet() }

        LazyVerticalGrid(
            columns = columns,
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(CommonAnimeItemDefaults.GridVerticalSpacer),
            horizontalArrangement = Arrangement.spacedBy(CommonAnimeItemDefaults.GridHorizontalSpacer),
            modifier = Modifier.fillMaxSize(),
        ) {
            state.items.forEach { (keyword, animes) ->
                item(key = "header-$keyword", span = { GridItemSpan(maxLineSpan) }) {
                    ListGroupHeader(
                        text = keyword.uppercase(),
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(
                    items = animes,
                    key = { index: Int, it: tachiyomi.domain.anime.model.Anime -> "anime-$keyword-${it.id}-$index" },
                ) { _: Int, anime: tachiyomi.domain.anime.model.Anime ->
                    val isFavorite = anime.id in state.favoriteIds
                    val isSelected = anime.id in selectionIds

                    when (displayMode) {
                        LibraryDisplayMode.ComfortableGrid -> {
                            BrowseSourceComfortableGridItem(
                                anime = anime,
                                isFavorite = isFavorite,
                                isSelected = isSelected,
                                onClick = { onAnimeClick(anime) },
                                onLongClick = { onAnimeLongClick(anime) },
                                usePanorama = usePanorama,
                            )
                        }
                        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                            BrowseSourceCompactGridItem(
                                anime = anime,
                                isFavorite = isFavorite,
                                isSelected = isSelected,
                                onClick = { onAnimeClick(anime) },
                                onLongClick = { onAnimeLongClick(anime) },
                                showTitle = displayMode is LibraryDisplayMode.CompactGrid,
                                usePanorama = usePanorama,
                            )
                        }
                        LibraryDisplayMode.List -> {
                            BrowseSourceListItem(
                                anime = anime,
                                isFavorite = isFavorite,
                                isSelected = isSelected,
                                onClick = { onAnimeClick(anime) },
                                onLongClick = { onAnimeLongClick(anime) },
                                entries = 0, // Not used for simple list item
                                containerHeight = 0,
                                usePanorama = usePanorama,
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
