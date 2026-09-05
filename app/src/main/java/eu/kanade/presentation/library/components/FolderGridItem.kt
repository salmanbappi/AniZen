package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.tachiyomi.ui.library.LibraryDisplayItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryDisplayMode
import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun FolderGridItem(
    folder: LibraryDisplayItem.Folder,
    displayMode: LibraryDisplayMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showTitle: Boolean = true,
    usePanorama: Boolean = false,
) {
    val items = folder.items
    val count = items.size

    val isCompact = displayMode is LibraryDisplayMode.CompactGrid || displayMode is LibraryDisplayMode.CoverOnlyGrid
    val ratio = if (usePanorama) AnimeCover.Panorama.ratio else AnimeCover.Book.ratio

    GridItemSelectable(
        isSelected = false,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (count > 0) {
                    val previewItems = items.take(4)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                if (previewItems.size > 0) FolderPreviewCover(previewItems[0], usePanorama)
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                if (previewItems.size > 1) FolderPreviewCover(previewItems[1], usePanorama)
                            }
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                if (previewItems.size > 2) FolderPreviewCover(previewItems[2], usePanorama)
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                if (previewItems.size > 3) {
                                    if (count > 4) {
                                        FolderPreviewMore(count - 3)
                                    } else {
                                        FolderPreviewCover(previewItems[3], usePanorama)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxSize(0.5f)
                    )
                }

                if (isCompact && showTitle) {
                    CoverTextOverlay(
                        title = folder.folder.name,
                        onClickContinueWatching = null,
                    )
                }
            }
            if (!isCompact && showTitle) {
                GridItemTitle(
                    title = folder.folder.name,
                    style = MaterialTheme.typography.bodySmall,
                    minLines = 2,
                    modifier = Modifier.padding(
                        top = 4.dp,
                        bottom = CommonAnimeItemDefaults.GridVerticalSpacer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FolderPreviewCover(item: LibraryItem, usePanorama: Boolean) {
    val anime = item.libraryAnime.anime
    val coverEntry = if (usePanorama) AnimeCover.Panorama else AnimeCover.Book
    
    coverEntry(
        data = anime,
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(4.dp),
        // Folder tiles are library items too: no palette extraction while scrolling. The
        // entry is chosen from the caller's flag, so no measured ratio is needed either.
        shouldExtractColor = false,
        measureRatio = false,
        ratio = coverEntry.ratio,
    )
}

@Composable
private fun FolderPreviewMore(moreCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$moreCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
    }
}
