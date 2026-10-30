package eu.kanade.presentation.library.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.domain.ui.UiPreferences
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref

@Composable
fun LibraryPager(
    state: PagerState,
    categories: ImmutableList<tachiyomi.domain.category.model.Category>,
    contentPadding: PaddingValues,
    hasActiveFilters: Boolean,
    selectedAnime: ImmutableList<LibraryAnime>,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getLibraryForPage: (Int) -> ImmutableList<eu.kanade.tachiyomi.ui.library.LibraryDisplayItem>,
    onClickAnime: (LibraryAnime) -> Unit,
    onLongClickAnime: (LibraryAnime, Long) -> Unit,
    onClickContinueWatching: ((LibraryAnime) -> Unit)?,
    onFolderClick: ((eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Folder) -> Unit)? = null,
    onFolderLongClick: ((eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Folder) -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns by remember(isLandscape) { getColumnsForOrientation(isLandscape) }

    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val globalPanorama by uiPreferences.panoramaCover().collectAsStatePref()
    val libraryMode by uiPreferences.libraryPanoramaMode().collectAsStatePref()
    val effectivePanorama = remember(globalPanorama, libraryMode) { libraryMode.resolve(globalPanorama) }

    val content: @Composable (Int) -> Unit = { containerHeight ->
        HorizontalPager(
            modifier = Modifier.fillMaxSize(),
            state = state,
            key = { categories.getOrNull(it)?.id ?: it.toLong() },
            verticalAlignment = Alignment.Top,
        ) { page ->
            val category = categories.getOrNull(page) ?: return@HorizontalPager
            val library = getLibraryForPage(page)

            if (library.isEmpty()) {
                LibraryPagerEmptyScreen(
                    searchQuery = searchQuery,
                    hasActiveFilters = hasActiveFilters,
                    contentPadding = contentPadding,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
                return@HorizontalPager
            }

            val displayMode by getDisplayMode(page)

            when (displayMode) {
                LibraryDisplayMode.List -> {
                    LibraryList(
                        items = library,
                        entries = columns,
                        containerHeight = 0,
                        contentPadding = contentPadding,
                        selection = selectedAnime,
                        onClick = onClickAnime,
                        onClickContinueWatching = onClickContinueWatching,
                        onLongClick = { onLongClickAnime(it, category.id) },
                        searchQuery = searchQuery,
                        onGlobalSearchClicked = onGlobalSearchClicked,
                        usePanorama = effectivePanorama,
                        onFolderClick = onFolderClick,
                        onFolderLongClick = onFolderLongClick,
                    )
                }
                LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                    LibraryCompactGrid(
                        items = library,
                        showTitle = displayMode is LibraryDisplayMode.CompactGrid,
                        columns = columns,
                        contentPadding = contentPadding,
                        selection = selectedAnime,
                        onClick = onClickAnime,
                        onClickContinueWatching = onClickContinueWatching,
                        onLongClick = { onLongClickAnime(it, category.id) },
                        searchQuery = searchQuery,
                        onGlobalSearchClicked = onGlobalSearchClicked,
                        usePanorama = effectivePanorama,
                        onFolderClick = onFolderClick,
                        onFolderLongClick = onFolderLongClick,
                    )
                }
                LibraryDisplayMode.ComfortableGrid -> {
                    LibraryComfortableGrid(
                        items = library,
                        columns = columns,
                        contentPadding = contentPadding,
                        selection = selectedAnime,
                        onClick = onClickAnime,
                        onLongClick = { onLongClickAnime(it, category.id) },
                        onClickContinueWatching = onClickContinueWatching,
                        searchQuery = searchQuery,
                        onGlobalSearchClicked = onGlobalSearchClicked,
                        usePanorama = effectivePanorama,
                        onFolderClick = onFolderClick,
                        onFolderLongClick = onFolderLongClick,
                    )
                }
                else -> {}
            }
        }
    }

    content(0)
}

@Composable
private fun LibraryPagerEmptyScreen(
    searchQuery: String?,
    hasActiveFilters: Boolean,
    contentPadding: PaddingValues,
    onGlobalSearchClicked: () -> Unit,
) {
    val msg = when {
        !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
        hasActiveFilters -> MR.strings.error_no_match
        else -> MR.strings.information_no_manga_category
    }

    Column(
        modifier = Modifier
            .padding(contentPadding + PaddingValues(8.dp))
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            GlobalSearchItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }

        EmptyScreen(
            stringRes = msg,
            modifier = Modifier.weight(1f),
        )
    }
}
