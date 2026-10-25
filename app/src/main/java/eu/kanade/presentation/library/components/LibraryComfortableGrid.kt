package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryDisplayItem
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.library.model.LibraryAnime

@Composable
internal fun LibraryComfortableGrid(
    items: ImmutableList<LibraryDisplayItem>,
    columns: Int,
    contentPadding: PaddingValues,
    selection: ImmutableList<LibraryAnime>,
    onClick: (LibraryAnime) -> Unit,
    onLongClick: (LibraryAnime) -> Unit,
    onClickContinueWatching: ((LibraryAnime) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    usePanorama: Boolean? = null,
    onFolderClick: ((LibraryDisplayItem.Folder) -> Unit)? = null,
    onFolderLongClick: ((LibraryDisplayItem.Folder) -> Unit)? = null,
) {
    val selectedIds = remember(selection) { selection.map { it.id }.toSet() }

    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        itemsIndexed(
            items = items,
            key = { _, item ->
                when (item) {
                    is LibraryDisplayItem.Anime -> "library-grid-${item.libraryItem.libraryAnime.anime.id}"
                    is LibraryDisplayItem.Folder -> "library-folder-${item.folder.id}"
                    is LibraryDisplayItem.Header -> "library-header-${item.name}"
                }
            },
            contentType = { _, item ->
                when (item) {
                    is LibraryDisplayItem.Anime -> "anime_library_comfortable_grid_item"
                    is LibraryDisplayItem.Folder -> "folder_library_comfortable_grid_item"
                    is LibraryDisplayItem.Header -> "header_library_comfortable_grid_item"
                }
            },
            span = { _, item ->
                when (item) {
                    is LibraryDisplayItem.Header -> GridItemSpan(maxLineSpan)
                    else -> GridItemSpan(1)
                }
            },
        ) { _, displayItem ->
            when (displayItem) {
                is LibraryDisplayItem.Anime -> {
                    val libraryItem = displayItem.libraryItem
                    val anime = libraryItem.libraryAnime.anime
                    AnimeComfortableGridItem(
                        isSelected = libraryItem.libraryAnime.id in selectedIds,
                        title = anime.title,
                        coverData = AnimeCover(
                            animeId = anime.id,
                            sourceId = anime.source,
                            isAnimeFavorite = anime.favorite,
                            ogUrl = anime.thumbnailUrl,
                            lastModified = anime.coverLastModified,
                        ),
                        coverBadgeStart = {
                            DownloadsBadge(count = libraryItem.downloadCount)
                            UnviewedBadge(count = libraryItem.unseenCount)
                        },
                        coverBadgeEnd = {
                            LanguageBadge(
                                isLocal = libraryItem.isLocal,
                                sourceLanguage = libraryItem.sourceLanguage,
                                showLanguageIcon = libraryItem.showLanguageIcon,
                            )
                            if (libraryItem.showSourceIcon) {
                                SourceIconBadge(source = libraryItem.domainSource)
                            }
                        },
                        onLongClick = { onLongClick(libraryItem.libraryAnime) },
                        onClick = { onClick(libraryItem.libraryAnime) },
                        onClickContinueWatching = if (onClickContinueWatching != null && libraryItem.unseenCount > 0) {
                            { onClickContinueWatching(libraryItem.libraryAnime) }
                        } else {
                            null
                        },
                        usePanorama = usePanorama,
                    )
                }
                is LibraryDisplayItem.Folder -> {
                    FolderGridItem(
                        folder = displayItem,
                        displayMode = tachiyomi.domain.library.model.LibraryDisplayMode.ComfortableGrid,
                        onClick = { onFolderClick?.invoke(displayItem) },
                        onLongClick = { onFolderLongClick?.invoke(displayItem) },
                        usePanorama = usePanorama ?: false,
                    )
                }
                is LibraryDisplayItem.Header -> {
                    Text(
                        text = displayItem.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
