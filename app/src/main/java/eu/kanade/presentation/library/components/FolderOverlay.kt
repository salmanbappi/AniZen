package eu.kanade.presentation.library.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.library.model.LibraryFolder

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun FolderOverlay(
    folder: LibraryFolder?,
    items: List<LibraryItem>,
    displayMode: tachiyomi.domain.library.model.LibraryDisplayMode,
    columns: Int,
    usePanorama: Boolean,
    onDismiss: () -> Unit,
    onRenameFolder: (String) -> Unit,
    onDeleteFolder: () -> Unit,
    onClickAnime: (eu.kanade.tachiyomi.ui.library.LibraryItem) -> Unit,
    onLongClickAnime: (eu.kanade.tachiyomi.ui.library.LibraryItem) -> Unit,
    onFolderActionClicked: (List<eu.kanade.tachiyomi.ui.library.LibraryItem>) -> Unit,
    onDownloadClicked: (List<eu.kanade.tachiyomi.ui.library.LibraryItem>, eu.kanade.presentation.anime.DownloadAction) -> Unit,
    onDeleteAnimeClicked: (List<eu.kanade.tachiyomi.ui.library.LibraryItem>) -> Unit,
    onMarkAsSeenClicked: (List<eu.kanade.tachiyomi.ui.library.LibraryItem>) -> Unit,
    onMarkAsUnseenClicked: (List<eu.kanade.tachiyomi.ui.library.LibraryItem>) -> Unit,
    onFavoriteClicked: (List<eu.kanade.tachiyomi.ui.library.LibraryItem>) -> Unit,
    onClickContinueWatching: ((tachiyomi.domain.library.model.LibraryAnime) -> Unit)? = null,
    onClickFilter: (() -> Unit)? = null,
) {
    val visible = folder != null
    var cachedFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var cachedItems by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }

    if (folder != null) {
        cachedFolder = folder
        cachedItems = items
    }

    var selectedItems by remember { mutableStateOf(emptySet<Long>()) }

    androidx.compose.runtime.LaunchedEffect(folder?.id) {
        selectedItems = emptySet()
    }

    val folder = cachedFolder
    val items = cachedItems

    val displayItems = remember(items) { items.map { eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Anime(it) }.toImmutableList() }
    val selectedAnime = remember(selectedItems, items) { items.map { it.libraryAnime }.filter { it.id in selectedItems }.toImmutableList() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
    ) {
        BackHandler(enabled = selectedItems.isNotEmpty() || visible) {
            if (selectedItems.isNotEmpty()) {
                selectedItems = emptySet()
            } else {
                onDismiss()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(color = Color.Black.copy(alpha = 0.85f))
                }
                .clickable(onClick = onDismiss),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(vertical = 48.dp)
                    .align(Alignment.Center)
                    .animateEnterExit(
                        enter = scaleIn(initialScale = 0.9f) + fadeIn(),
                        exit = scaleOut(targetScale = 0.9f) + fadeOut(),
                    )
                    .clickable(enabled = false) {}, // consume clicks to prevent dismiss
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                if (folder != null) {
                    var showRenameDialog by remember { mutableStateOf(false) }
                    var showDeleteDialog by remember { mutableStateOf(false) }

                    if (showRenameDialog) {
                        var name by remember { mutableStateOf(folder.name) }
                        AlertDialog(
                            onDismissRequest = { showRenameDialog = false },
                            confirmButton = {
                                TextButton(
                                    enabled = name.isNotBlank() && name != folder.name,
                                    onClick = {
                                        onRenameFolder(name.trim())
                                        showRenameDialog = false
                                    },
                                ) {
                                    Text(text = "Rename")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRenameDialog = false }) {
                                    Text(text = "Cancel")
                                }
                            },
                            title = { Text(text = "Rename folder") },
                            text = {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text(text = "Name") },
                                    singleLine = true,
                                )
                            },
                        )
                    }

                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    onDeleteFolder()
                                    showDeleteDialog = false
                                }) {
                                    Text(text = "Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteDialog = false }) {
                                    Text(text = "Cancel")
                                }
                            },
                            title = { Text(text = "Delete folder") },
                            text = { Text(text = "Are you sure you want to delete this folder? Anime inside will be returned to the library.") },
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selectedItems.isNotEmpty()) {
                                IconButton(onClick = { selectedItems = emptySet() }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Clear",
                                    )
                                }
                                Text(
                                    text = "${selectedItems.size} selected",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { selectedItems = items.map { it.libraryAnime.id }.toSet() }) {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList, // Replace with "Select All" if available
                                        contentDescription = "Select all",
                                    )
                                }
                            } else {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { showRenameDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Rename folder",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(onClick = { showDeleteDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Delete folder",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (onClickFilter != null) {
                                    IconButton(onClick = onClickFilter) {
                                        Icon(
                                            imageVector = Icons.Outlined.FilterList,
                                            contentDescription = "Filter, Sort, Display, Group",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "${items.size} titles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        ) {
                            val containerHeight = constraints.maxHeight

                            val onClick: (tachiyomi.domain.library.model.LibraryAnime) -> Unit = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems = if (selectedItems.contains(it.id)) {
                                        selectedItems - it.id
                                    } else {
                                        selectedItems + it.id
                                    }
                                } else {
                                    val libItem = items.find { item -> item.libraryAnime.id == it.id }
                                    if (libItem != null) onClickAnime(libItem)
                                }
                            }
                            val onLongClick: (tachiyomi.domain.library.model.LibraryAnime) -> Unit = {
                                selectedItems = if (selectedItems.contains(it.id)) {
                                    selectedItems - it.id
                                } else {
                                    selectedItems + it.id
                                }
                            }

                            val onClickContinueWatchingGrid: ((tachiyomi.domain.library.model.LibraryAnime) -> Unit)? =
                                if (onClickContinueWatching != null) { it -> onClickContinueWatching(it) } else null

                            when (displayMode) {
                                tachiyomi.domain.library.model.LibraryDisplayMode.List -> {
                                    LibraryList(
                                        items = displayItems,
                                        entries = columns,
                                        containerHeight = containerHeight,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                        selection = selectedAnime,
                                        onClick = onClick,
                                        onLongClick = onLongClick,
                                        onClickContinueWatching = onClickContinueWatchingGrid,
                                        searchQuery = null,
                                        onGlobalSearchClicked = {},
                                        usePanorama = usePanorama,
                                    )
                                }
                                tachiyomi.domain.library.model.LibraryDisplayMode.CompactGrid -> {
                                    LibraryCompactGrid(
                                        items = displayItems,
                                        showTitle = true,
                                        columns = columns,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                        selection = selectedAnime,
                                        onClick = onClick,
                                        onLongClick = onLongClick,
                                        onClickContinueWatching = onClickContinueWatchingGrid,
                                        searchQuery = null,
                                        onGlobalSearchClicked = {},
                                        usePanorama = usePanorama,
                                    )
                                }
                                tachiyomi.domain.library.model.LibraryDisplayMode.ComfortableGrid -> {
                                    LibraryComfortableGrid(
                                        items = displayItems,
                                        columns = columns,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                        selection = selectedAnime,
                                        onClick = onClick,
                                        onLongClick = onLongClick,
                                        onClickContinueWatching = onClickContinueWatchingGrid,
                                        searchQuery = null,
                                        onGlobalSearchClicked = {},
                                        usePanorama = usePanorama,
                                    )
                                }
                                tachiyomi.domain.library.model.LibraryDisplayMode.CoverOnlyGrid -> {
                                    LibraryCoverOnlyGrid(
                                        items = displayItems,
                                        columns = columns,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                        selection = selectedAnime,
                                        onClick = onClick,
                                        onLongClick = onLongClick,
                                        onClickContinueWatching = onClickContinueWatchingGrid,
                                        searchQuery = null,
                                        onGlobalSearchClicked = {},
                                        usePanorama = usePanorama,
                                    )
                                }
                            }
                        }

                        if (selectedItems.isNotEmpty()) {
                            eu.kanade.presentation.anime.components.LibraryBottomActionMenu(
                                modifier = Modifier.padding(top = 12.dp),
                                visible = selectedItems.isNotEmpty(),
                                onChangeCategoryClicked = { /* Not needed for folder view */ },
                                onMarkAsSeenClicked = { onMarkAsSeenClicked(items.filter { selectedItems.contains(it.libraryAnime.id) }) },
                                onMarkAsUnseenClicked = { onMarkAsUnseenClicked(items.filter { selectedItems.contains(it.libraryAnime.id) }) },
                                onFavoriteClicked = { onFavoriteClicked(items.filter { selectedItems.contains(it.libraryAnime.id) }) },
                                onDownloadClicked = { action ->
                                    onDownloadClicked(items.filter { selectedItems.contains(it.libraryAnime.id) }, action)
                                },
                                onDeleteClicked = { onDeleteAnimeClicked(items.filter { selectedItems.contains(it.libraryAnime.id) }) },
                                onMigrateClicked = { /* Bulk migrate from folder? */ },
                                onMergeClicked = { },
                                onSelectionUpdateClicked = { },
                                onClickCollectRecommendations = { },
                                onClickResetInfo = { },
                                onFolderClicked = {
                                    val selectedLibraryItems = items.filter { selectedItems.contains(it.libraryAnime.id) }
                                    onFolderActionClicked(selectedLibraryItems)
                                    selectedItems = emptySet()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
