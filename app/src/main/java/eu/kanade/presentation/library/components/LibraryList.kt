package eu.kanade.presentation.library.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryDisplayItem
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.util.plus

@Composable
internal fun LibraryList(
    items: ImmutableList<LibraryDisplayItem>,
    entries: Int,
    containerHeight: Int,
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

    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (!searchQuery.isNullOrEmpty()) {
                GlobalSearchItem(
                    modifier = Modifier.fillMaxWidth(),
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                )
            }
        }

        itemsIndexed(
            items = items,
            key = { _, item ->
                when (item) {
                    is LibraryDisplayItem.Anime -> "library-list-${item.libraryItem.libraryAnime.anime.id}"
                    is LibraryDisplayItem.Folder -> "library-list-folder-${item.folder.id}"
                    is LibraryDisplayItem.Header -> "library-list-header-${item.name}"
                }
            },
            contentType = { _, item ->
                when (item) {
                    is LibraryDisplayItem.Anime -> "anime_library_list_item"
                    is LibraryDisplayItem.Folder -> "folder_library_list_item"
                    is LibraryDisplayItem.Header -> "header_library_list_item"
                }
            },
        ) { _, displayItem ->
            when (displayItem) {
                is LibraryDisplayItem.Anime -> {
                    val libraryItem = displayItem.libraryItem
                    val anime = libraryItem.libraryAnime.anime
                    AnimeListItem(
                        isSelected = libraryItem.libraryAnime.id in selectedIds,
                        title = anime.title,
                        coverData = AnimeCover(
                            animeId = anime.id,
                            sourceId = anime.source,
                            isAnimeFavorite = anime.favorite,
                            ogUrl = anime.thumbnailUrl,
                            lastModified = anime.coverLastModified,
                        ),
                        badge = {
                            DownloadsBadge(count = libraryItem.downloadCount)
                            UnviewedBadge(count = libraryItem.unseenCount)
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
                        entries = entries,
                        containerHeight = containerHeight,
                        usePanorama = usePanorama,
                    )
                }
                is LibraryDisplayItem.Folder -> {
                    FolderListItem(
                        folder = displayItem,
                        onClick = { onFolderClick?.invoke(displayItem) },
                        onLongClick = { onFolderLongClick?.invoke(displayItem) },
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

@Composable
fun FolderListItem(
    folder: LibraryDisplayItem.Folder,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = folder.folder.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
