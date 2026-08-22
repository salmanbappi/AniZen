package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import eu.kanade.presentation.library.components.AnimeListItem
import eu.kanade.presentation.library.components.CommonAnimeItemDefaults
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    entries: Int,
    contentPadding: PaddingValues,
    onAnimeClick: (Anime, Int) -> Unit,
    onAnimeLongClick: (Anime, Int) -> Unit,
    selection: List<Anime>,
    favoriteIds: ImmutableSet<Long>,
    onBatchIncrement: (Int) -> Unit = {},
    usePanorama: Boolean = false,
    firstItemFocusRequester: FocusRequester? = null,
    selectedChipFocusRequester: FocusRequester? = null,
) {
    val selectionIds = remember(selection) { selection.map { it.id }.toSet() }
    LazyColumn(
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (animeList.loadState.prepend is LoadState.Loading) {
            item(key = "browse-list-load-prepend") {
                BrowseSourceLoadingItem()
            }
        }

        items(
            count = animeList.itemCount,
            key = { index -> animeList.peek(index)?.value?.id ?: "placeholder-$index" },
            contentType = { index -> if (animeList.peek(index) != null) "anime" else "placeholder" },
        ) { index ->
            val animeFlow = animeList[index] ?: return@items
            val anime = animeFlow.value
            onBatchIncrement(index)

            val currentOnAnimeClick = remember(onAnimeClick, anime.id, index) { 
                { onAnimeClick(anime, index) } 
            }
            val currentOnAnimeLongClick = remember(onAnimeLongClick, anime.id, index) { 
                { onAnimeLongClick(anime, index) } 
            }

            val itemModifier = (if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                .then(
                    if (index == 0 && selectedChipFocusRequester != null) {
                        Modifier.focusProperties {
                            up = selectedChipFocusRequester
                        }
                    } else {
                        Modifier
                    }
                )

            BrowseSourceListItem(
                anime = anime,
                isFavorite = anime.id in favoriteIds,
                isSelected = anime.id in selectionIds,
                onClick = currentOnAnimeClick,
                onLongClick = currentOnAnimeLongClick,
                entries = entries,
                containerHeight = 96,
                usePanorama = usePanorama,
                modifier = itemModifier,
            )
        }

        if (animeList.loadState.refresh is LoadState.Loading || animeList.loadState.append is LoadState.Loading) {
            item(key = "browse-list-load-append") {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
internal fun BrowseSourceListItem(
    anime: Anime,
    isFavorite: Boolean,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    entries: Int,
    containerHeight: Int,
    usePanorama: Boolean = false,
    modifier: Modifier = Modifier,
) {
    eu.kanade.tachiyomi.util.system.PerformanceBenchmarkHelper.countRecomposition("BrowseSourceListItem")
    val badge: @Composable () -> Unit = remember(isFavorite) {
        { InLibraryBadge(enabled = isFavorite) }
    }
    AnimeListItem(
        modifier = modifier,
        title = anime.title,
        isSelected = isSelected,
        coverData = remember(anime.id, isFavorite) {
            AnimeCover(
                animeId = anime.id,
                sourceId = anime.source,
                isAnimeFavorite = isFavorite,
                ogUrl = anime.thumbnailUrl,
                lastModified = anime.coverLastModified,
            )
        },
        coverAlpha = if (isFavorite) CommonAnimeItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = badge,
        onLongClick = onLongClick,
        onClick = onClick,
        entries = entries,
        containerHeight = containerHeight,
        usePanorama = usePanorama,
    )
}
