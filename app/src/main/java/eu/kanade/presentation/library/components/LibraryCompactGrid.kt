package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastAny
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.anime.model.asAnimeCover
import eu.kanade.tachiyomi.ui.library.LibraryDisplayItem
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource

@Composable
fun LibraryCompactGrid(
    items: ImmutableList<LibraryDisplayItem>,
    showTitle: Boolean,
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
                    is LibraryDisplayItem.Anime -> item.libraryItem.libraryAnime.anime.id
                    is LibraryDisplayItem.Folder -> -item.folder.id
                    is LibraryDisplayItem.Header -> item.name.hashCode().toLong()
                }
            },
            contentType = { _, item -> 
                when (item) {
                    is LibraryDisplayItem.Anime -> "anime_library_compact_grid_item"
                    is LibraryDisplayItem.Folder -> "folder_library_compact_grid_item"
                    is LibraryDisplayItem.Header -> "header_library_compact_grid_item"
                }
            },
            span = { _, item ->
                when (item) {
                    is LibraryDisplayItem.Header -> GridItemSpan(maxLineSpan)
                    else -> GridItemSpan(1)
                }
            }
        ) { _, displayItem ->
            when (displayItem) {
                is LibraryDisplayItem.Anime -> {
                    val libraryItem = displayItem.libraryItem
                    val anime = libraryItem.libraryAnime.anime
                    AnimeCompactGridItem(
                        isSelected = libraryItem.libraryAnime.id in selectedIds,
                        title = anime.title.takeIf { showTitle },
                        coverData = anime.asAnimeCover(),
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
                        displayMode = tachiyomi.domain.library.model.LibraryDisplayMode.CompactGrid,
                        onClick = { onFolderClick?.invoke(displayItem) },
                        onLongClick = { onFolderLongClick?.invoke(displayItem) },
                        showTitle = showTitle,
                        usePanorama = usePanorama ?: false,
                    )
                }
                is LibraryDisplayItem.Header -> {
                    Text(
                        text = displayItem.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

