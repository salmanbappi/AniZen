package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import kotlinx.coroutines.launch
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.collections.immutable.toImmutableList
import sh.calvin.reorderable.*
import tachiyomi.domain.source.model.FeedSavedSearchUpdate

class FeedManageScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { FeedManageScreenModel() }
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        var deleteDialogItem by remember { mutableStateOf<FeedSavedSearch?>(null) }
        var editFeedItem by remember { mutableStateOf<FeedManageScreenModel.FeedItem?>(null) }
        var moveFeedItem by remember { mutableStateOf<FeedManageScreenModel.FeedItem?>(null) }

        // Category Management States
        var showAddCategoryDialog by remember { mutableStateOf(false) }
        var showRenameCategoryDialog by remember { mutableStateOf<Long?>(null) }
        var categoryToDelete by remember { mutableStateOf<Long?>(null) }

        if (state.categories.isEmpty()) {
            LoadingScreen()
            return
        }

        val pagerState = rememberPagerState { state.categories.size }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Manage Feed",
                    navigateUp = { navigator.pop() },
                    actions = {
                        IconButton(onClick = { showAddCategoryDialog = true }) {
                            Icon(imageVector = Icons.Outlined.Add, contentDescription = "Add Category")
                        }

                        val currentCategoryId = state.categories.getOrNull(pagerState.currentPage)?.id
                        val currentCategoryName = state.categories.getOrNull(pagerState.currentPage)?.name
                        if (currentCategoryId != null) {
                            if (currentCategoryId != 1L && currentCategoryName != "Global") {
                                IconButton(onClick = { showRenameCategoryDialog = currentCategoryId }) {
                                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = "Rename Category")
                                }
                                IconButton(onClick = { categoryToDelete = currentCategoryId }) {
                                    Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Delete Category")
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (state.categories.size > 1) {
                    ScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage.coerceIn(0, state.categories.lastIndex),
                        edgePadding = 0.dp,
                        divider = {},
                    ) {
                        state.categories.forEachIndexed { index, category ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(text = category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = true,
                ) { page ->
                    val category = state.categories.getOrNull(page)
                    if (category != null) {
                        val items = state.items[category.id]
                        if (items.isNullOrEmpty()) {
                            EmptyScreen(
                                stringRes = SYMR.strings.feed_tab_empty,
                            )
                        } else {
                            var currentItems by remember(items) { mutableStateOf(items) }
                            val lazyListState = rememberLazyListState()
                            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                                currentItems = currentItems.toMutableList().apply {
                                    add(to.index, removeAt(from.index))
                                }.toImmutableList()
                                screenModel.reorder(category.id, currentItems.map { it.feed })
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = lazyListState,
                            ) {
                                itemsIndexed(
                                    items = currentItems,
                                    key = { index, item -> "feed-${item.feed.id}-$index" },
                                ) { index, item ->
                                    ReorderableItem(
                                        state = reorderableState,
                                        key = "feed-${item.feed.id}-$index",
                                    ) { isDragging ->
                                        FeedManageItem(
                                            title = item.title,
                                            type = item.subtitle,
                                            onDuplicate = { screenModel.duplicate(item.feed) },
                                            onDelete = { deleteDialogItem = item.feed },
                                            onClick = { editFeedItem = item },
                                            dragHandle = {
                                                Icon(
                                                    imageVector = Icons.Outlined.DragHandle,
                                                    contentDescription = null,
                                                    modifier = Modifier.draggableHandle()
                                                )
                                            },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add Category Dialog
        if (showAddCategoryDialog) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddCategoryDialog = false },
                title = { Text(text = "New Category") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            screenModel.createCategory(name)
                            showAddCategoryDialog = false
                        },
                        enabled = name.isNotBlank()
                    ) {
                        Text(text = stringResource(MR.strings.action_add))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddCategoryDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                }
            )
        }

        // Rename Category Dialog
        if (showRenameCategoryDialog != null) {
            val categoryId = showRenameCategoryDialog!!
            val currentCategory = state.categories.find { it.id == categoryId }
            var name by remember { mutableStateOf(currentCategory?.name ?: "") }
            AlertDialog(
                onDismissRequest = { showRenameCategoryDialog = null },
                title = { Text(text = if (categoryId == 1L) "Rename Global Category" else "Rename Category") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            screenModel.renameCategory(categoryId, name)
                            showRenameCategoryDialog = null
                        },
                        enabled = name.isNotBlank()
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameCategoryDialog = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                }
            )
        }

        // Delete Category Dialog
        if (categoryToDelete != null) {
            val category = state.categories.find { it.id == categoryToDelete }
            AlertDialog(
                onDismissRequest = { categoryToDelete = null },
                title = { Text(text = "Delete Category?") },
                text = { Text(text = "Are you sure you want to delete '${category?.name}'? Items in this category will also be deleted.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            screenModel.deleteCategory(categoryToDelete!!)
                            categoryToDelete = null
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { categoryToDelete = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        // Move Feed Item Dialog
        if (moveFeedItem != null) {
            val feed = moveFeedItem!!.feed
            AlertDialog(
                onDismissRequest = { moveFeedItem = null },
                title = { Text(text = "Move to Category") },
                text = {
                    LazyColumn {
                        itemsIndexed(state.categories, key = { _, category -> category.id }) { _, category ->
                             ListItem(
                                headlineContent = { Text(category.name) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (feed.category != category.id) {
                                            screenModel.updateFeedCategory(feed, category.id)
                                        }
                                        moveFeedItem = null
                                    }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { moveFeedItem = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                }
            )
        }

        if (deleteDialogItem != null) {
            val feed = deleteDialogItem!!
            AlertDialog(
                onDismissRequest = { deleteDialogItem = null },
                title = { Text(text = "Delete feed item?") },
                text = { Text(text = "Are you sure you want to remove this from your feed?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            screenModel.delete(feed)
                            deleteDialogItem = null
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteDialogItem = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (editFeedItem != null) {
            val feedItem = editFeedItem!!
            val feed = feedItem.feed
            val source = feedItem.source

            val savedSearches by produceState<List<SavedSearch>?>(initialValue = null, feed.source) {
                value = screenModel.getSourceSavedSearches(feed.source)
            }

            AlertDialog(
                onDismissRequest = { editFeedItem = null },
                title = { Text(text = stringResource(MR.strings.action_edit)) },
                text = {
                    if (savedSearches == null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            // Move Option
                            ListItem(
                                headlineContent = { Text("Move to Category...") },
                                leadingContent = { Icon(Icons.Outlined.Folder, null) },
                                modifier = Modifier.clickable {
                                    editFeedItem = null
                                    moveFeedItem = feedItem
                                }
                            )
                            HorizontalDivider()

                            val currentType = FeedSavedSearch.Type.from(feed.type)
                            val isSavedSearch = feed.savedSearch != null

                            if (source is AnimeCatalogueSource && source.supportsLatest) {
                                RadioMenuItem(
                                    text = { Text(text = stringResource(MR.strings.latest)) },
                                    isChecked = !isSavedSearch && currentType == FeedSavedSearch.Type.Latest,
                                ) {
                                    screenModel.updateFeed(feed, FeedSavedSearch.Type.Latest, null)
                                    editFeedItem = null
                                }
                            }
                            RadioMenuItem(
                                text = { Text(text = stringResource(MR.strings.popular)) },
                                isChecked = !isSavedSearch && currentType == FeedSavedSearch.Type.Popular,
                            ) {
                                screenModel.updateFeed(feed, FeedSavedSearch.Type.Popular, null)
                                editFeedItem = null
                            }

                            if (!savedSearches.isNullOrEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    text = stringResource(SYMR.strings.saved_searches),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                )
                                savedSearches!!.forEach { savedSearch ->
                                    RadioMenuItem(
                                        text = { Text(text = savedSearch.name) },
                                        isChecked = feed.savedSearch == savedSearch.id,
                                    ) {
                                        screenModel.updateFeed(feed, FeedSavedSearch.Type.SavedSearch, savedSearch.id)
                                        editFeedItem = null
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { editFeedItem = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun FeedManageItem(
    title: String,
    type: String,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    dragHandle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dragHandle()
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDuplicate) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(MR.strings.copy),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }
    }
}
