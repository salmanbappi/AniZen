package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.domain.source.model.FeedSavedSearchCategory
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.sourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { SourcesScreenModel() }

    val globalSearch = stringResource(MR.strings.action_global_search)
    val actionFilter = stringResource(MR.strings.action_filter)

    return remember(globalSearch, actionFilter) {
        TabContent(
            titleRes = MR.strings.label_sources,
            searchEnabled = false,
            actions = persistentListOf(
                AppBar.Action(
                    title = globalSearch,
                    icon = Icons.Outlined.TravelExplore,
                    onClick = { navigator.push(GlobalSearchScreen()) },
                ),
                AppBar.Action(
                    title = actionFilter,
                    icon = Icons.Outlined.FilterList,
                    onClick = { navigator.push(SourcesFilterScreen()) },
                ),
            ),
            content = { contentPadding, snackbarHostState ->
                val state by screenModel.state.collectAsStateWithLifecycle()
                val onClickItem: (tachiyomi.domain.source.model.Source, eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing) -> Unit = remember(navigator) {
                    { source, listing ->
                        navigator.push(BrowseSourceScreen(source.id, listing.query))
                    }
                }
                SourcesScreen(
                    state = state,
                    contentPadding = contentPadding,
                    onClickItem = onClickItem,
                    onClickPin = screenModel::togglePin,
                    onLongClickItem = screenModel::showSourceDialog,
                    onChangeSearchQuery = screenModel::search,
                    onToggleNsfwOnly = screenModel::toggleNsfwOnly,
                )

                when (val dialog = state.dialog) {
                    is SourcesScreenModel.Dialog.SourceOptions -> {
                        val source = dialog.source
                        SourceOptionsDialog(
                            source = source,
                            onClickPin = {
                                screenModel.togglePin(source)
                                screenModel.closeDialog()
                            },
                            onClickDisable = {
                                screenModel.toggleSource(source)
                                screenModel.closeDialog()
                            },
                            onClickAddToFeed = {
                                screenModel.onAddToFeedClicked(source)
                            },
                            onDismiss = screenModel::closeDialog,
                        )
                    }
                    is SourcesScreenModel.Dialog.FeedCategorySelect -> {
                        val source = dialog.source
                        AlertDialog(
                            onDismissRequest = screenModel::closeDialog,
                            title = { Text(text = "Add to Feed Category") },
                            text = {
                                val dialogId = remember(source.id) { source.id }
                                LazyColumn {
                                    itemsIndexed(
                                        items = state.categories,
                                        key = { index, it -> "source-category-$dialogId-${it.id}-$index" }
                                    ) { _, category ->
                                        ListItem(
                                            headlineContent = { Text(category.name) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    screenModel.addToFeed(source, category.id)
                                                    screenModel.closeDialog()
                                                }
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = screenModel::closeDialog) {
                                    Text(text = stringResource(MR.strings.action_cancel))
                                }
                            }
                        )
                    }
                    null -> {}
                }

                val internalErrString = stringResource(MR.strings.internal_error)
                LaunchedEffect(Unit) {
                    screenModel.events.collectLatest { event ->
                        when (event) {
                            SourcesScreenModel.Event.FailedFetchingSources -> {
                                launch { snackbarHostState.showSnackbar(internalErrString) }
                            }
                        }
                    }
                }
            },
        )
    }
}
