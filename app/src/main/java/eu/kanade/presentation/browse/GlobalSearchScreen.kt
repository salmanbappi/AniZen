package eu.kanade.presentation.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.GlobalSearchToolbar
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.anime.model.Anime
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

@Composable
fun GlobalSearchScreen(
    state: SearchScreenModel.State,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onToggleResults: () -> Unit,
    getAnime: @Composable (Anime) -> State<Anime>,
    onClickSource: (CatalogueSource) -> Unit,
    onClickItem: (Anime) -> Unit,
    onLongClickItem: (Anime) -> Unit,
    selection: List<Anime> = emptyList(),
    onClearSelection: () -> Unit = {},
    onChangeCategory: () -> Unit = {},
) {
    BackHandler(enabled = selection.isNotEmpty(), onBack = onClearSelection)

    Scaffold(
        topBar = { scrollBehavior ->
            if (selection.isEmpty()) {
                GlobalSearchToolbar(
                    searchQuery = state.searchQuery,
                    progress = state.progress,
                    total = state.total,
                    navigateUp = navigateUp,
                    onChangeSearchQuery = onChangeSearchQuery,
                    onSearch = onSearch,
                    sourceFilter = state.actualSourceFilter,
                    onChangeSearchFilter = onChangeSearchFilter,
                    onlyShowHasResults = state.onlyShowHasResults,
                    onToggleResults = onToggleResults,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                BulkSelectionToolbar(
                    selectedCount = selection.size,
                    isRunning = false,
                    onClickClearSelection = onClearSelection,
                    onChangeCategoryClick = onChangeCategory,
                )
            }
        },
    ) { paddingValues ->
        GlobalSearchContent(
            items = state.filteredItems,
            contentPadding = paddingValues,
            getAnime = getAnime,
            onClickSource = onClickSource,
            onClickItem = onClickItem,
            onLongClickItem = onLongClickItem,
            selection = selection,
        )
    }
}

@Composable
internal fun GlobalSearchContent(
    items: Map<CatalogueSource, SearchItemResult>,
    contentPadding: PaddingValues,
    getAnime: @Composable (Anime) -> State<Anime>,
    onClickSource: (CatalogueSource) -> Unit,
    onClickItem: (Anime) -> Unit,
    onLongClickItem: (Anime) -> Unit,
    fromSourceId: Long? = null,
    selection: List<Anime> = emptyList(),
) {
    val uiPreferences = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
    val animatedTransitions by uiPreferences.animatedTransitions().collectAsStatePref()

    LazyColumn(
        contentPadding = contentPadding,
    ) {
        items.forEach { (source, result) ->
            item(key = "global-search-${source.id}") {
                GlobalSearchResultItem(
                    title = fromSourceId?.let {
                        "▶ ${source.name}".takeIf { source.id == fromSourceId }
                    } ?: source.name,
                    subtitle = LocaleHelper.getLocalizedDisplayName(source.lang),
                    onClick = { onClickSource(source) },
                    modifier = if (animatedTransitions) Modifier.animateItem() else Modifier,
                ) {
                    when (result) {
                        SearchItemResult.Loading -> {
                            GlobalSearchLoadingResultItem()
                        }
                        is SearchItemResult.Success -> {
                            GlobalSearchCardRow(
                                titles = result.result,
                                getAnime = getAnime,
                                onClick = onClickItem,
                                onLongClick = onLongClickItem,
                                selection = selection,
                            )
                        }
                        is SearchItemResult.Error -> {
                            GlobalSearchErrorResultItem(message = result.throwable.message)
                        }
                    }
                }
            }
        }
    }
}
