package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.AnimeCompactGridItem
import eu.kanade.presentation.library.components.CommonAnimeItemDefaults
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceCompactGrid(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onAnimeClick: (Anime, Int) -> Unit,
    onAnimeLongClick: (Anime, Int) -> Unit,
    selection: List<Anime>,
    favoriteIds: ImmutableSet<Long>,
    onBatchIncrement: (Int) -> Unit = {},
    showTitle: Boolean = true,
    usePanorama: Boolean? = null,
    firstItemFocusRequester: FocusRequester? = null,
    selectedChipFocusRequester: FocusRequester? = null,
    columnsCount: Int = 0,
) {
    val selectionIds = remember(selection) { selection.map { it.id }.toSet() }
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonAnimeItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonAnimeItemDefaults.GridHorizontalSpacer),
    ) {
        if (animeList.loadState.prepend is LoadState.Loading) {
            item(key = "browse-grid-compact-load-prepend", span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(
            count = animeList.itemCount,
            key = { index -> animeList.peek(index)?.value?.id ?: "placeholder-$index" },
            contentType = { index -> if (animeList.peek(index) != null) "anime" else "placeholder" },
        ) { index ->
            val anime by animeList[index]?.collectAsState() ?: return@items
            onBatchIncrement(index)

            val currentOnAnimeClick = remember(onAnimeClick, anime, index) { 
                { onAnimeClick(anime, index) } 
            }
            val currentOnAnimeLongClick = remember(onAnimeLongClick, anime, index) { 
                { onAnimeLongClick(anime, index) } 
            }

            val topRowThreshold = if (columnsCount > 0) columnsCount else 6
            val itemModifier = (if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                .then(
                    if (index < topRowThreshold && selectedChipFocusRequester != null) {
                        Modifier.focusProperties {
                            up = selectedChipFocusRequester
                        }
                    } else {
                        Modifier
                    }
                )

            BrowseSourceCompactGridItem(
                anime = anime,
                isFavorite = anime.id in favoriteIds,
                isSelected = anime.id in selectionIds,
                onClick = currentOnAnimeClick,
                onLongClick = currentOnAnimeLongClick,
                showTitle = showTitle,
                usePanorama = usePanorama,
                modifier = itemModifier,
            )
        }

        if (animeList.loadState.refresh is LoadState.Loading || animeList.loadState.append is LoadState.Loading) {
            item(key = "browse-grid-compact-load-append", span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
internal fun BrowseSourceCompactGridItem(
    anime: Anime,
    isFavorite: Boolean,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    showTitle: Boolean = true,
    usePanorama: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    AnimeCompactGridItem(
        modifier = modifier,
        title = anime.title.takeIf { showTitle },
        coverData = remember(anime.id, isFavorite) {
            anime.asAnimeCover().copy(isAnimeFavorite = isFavorite)
        },
        coverAlpha = if (isFavorite) CommonAnimeItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = {
            InLibraryBadge(enabled = isFavorite)
        },
        onLongClick = onLongClick,
        onClick = onClick,
        isSelected = isSelected,
        usePanorama = usePanorama,
    )
}
