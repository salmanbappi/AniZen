package mihon.feature.announcements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.i18n.stringResource

object AnnouncementsTab {

    @Composable
    fun Content(
        contentPadding: PaddingValues,
        screenModel: AnnouncementsScreenModel,
    ) {
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        when (val current = state) {
            is AnnouncementsScreenModel.State.Loading -> LoadingState(contentPadding)
            is AnnouncementsScreenModel.State.Error -> ErrorState(
                contentPadding = contentPadding,
                onRetry = { screenModel.load(forceRefresh = true) },
                onShowCached = { screenModel.showCachedData() },
            )
            is AnnouncementsScreenModel.State.Success -> SuccessState(
                contentPadding = contentPadding,
                state = current,
                onCardClick = { entry ->
                    navigator.push(AnnouncementDetailScreen(entry))
                },
            )
        }

        if (screenModel.showFiltersDialog && state is AnnouncementsScreenModel.State.Success) {
            AnnouncementsFilterSheet(
                state = state as AnnouncementsScreenModel.State.Success,
                onDismissRequest = screenModel::closeFilters,
                onSelectCategory = screenModel::selectCategory,
                onSelectSort = screenModel::setSort,
                onSelectYear = screenModel::selectYear,
                onSetIncludeAdult = screenModel::setIncludeAdult,
                onSetAutoRefresh = screenModel::setAutoRefresh,
                onReset = screenModel::resetFilters,
            )
        }
    }

    @Composable
    private fun LoadingState(contentPadding: PaddingValues) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }

    @Composable
    private fun ErrorState(
        contentPadding: PaddingValues,
        onRetry: () -> Unit,
        onShowCached: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Couldn't load announcements",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "AniList is currently unavailable or rate limited. Showing cached data (if available).",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
                Text("Try Again")
            }
            OutlinedButton(onClick = onShowCached, modifier = Modifier.padding(top = 8.dp)) {
                Text("Show Cached Data")
            }
        }
    }

    @Composable
    private fun SuccessState(
        contentPadding: PaddingValues,
        state: AnnouncementsScreenModel.State.Success,
        onCardClick: (AnnouncementEntry) -> Unit,
    ) {
        LazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.filteredEntries, key = { it.mediaId }) { entry ->
                AnnouncementCard(entry = entry, onClick = { onCardClick(entry) })
            }
        }
    }

    @Composable
    private fun AnnouncementsFilterSheet(
        state: AnnouncementsScreenModel.State.Success,
        onDismissRequest: () -> Unit,
        onSelectCategory: (AnnouncementCategory?) -> Unit,
        onSelectSort: (AnnouncementSort) -> Unit,
        onSelectYear: (Int?) -> Unit,
        onSetIncludeAdult: (Boolean) -> Unit,
        onSetAutoRefresh: (AnnouncementAutoRefresh) -> Unit,
        onReset: () -> Unit,
    ) {
        TabbedDialog(
            onDismissRequest = onDismissRequest,
            tabTitles = persistentListOf(
                stringResource(MR.strings.action_filter),
                stringResource(MR.strings.action_sort),
            ),
            tabOverflowMenuContent = { closeMenu ->
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.action_reset)) },
                    onClick = {
                        onReset()
                        closeMenu()
                    },
                )
            },
        ) { page ->
            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    0 -> FilterPage(
                        state = state,
                        onSelectCategory = onSelectCategory,
                        onSelectYear = onSelectYear,
                        onSetIncludeAdult = onSetIncludeAdult,
                        onSetAutoRefresh = onSetAutoRefresh,
                    )
                    1 -> SortPage(
                        state = state,
                        onSelectSort = onSelectSort,
                    )
                }
            }
        }
    }

    @Composable
    private fun FilterPage(
        state: AnnouncementsScreenModel.State.Success,
        onSelectCategory: (AnnouncementCategory?) -> Unit,
        onSelectYear: (Int?) -> Unit,
        onSetIncludeAdult: (Boolean) -> Unit,
        onSetAutoRefresh: (AnnouncementAutoRefresh) -> Unit,
    ) {
        HeadingItem(text = "Category")
        RadioItem(
            label = "All",
            selected = state.selectedCategory == null,
            onClick = { onSelectCategory(null) },
        )
        AnnouncementCategory.entries.forEach { category ->
            RadioItem(
                label = category.displayName(),
                selected = state.selectedCategory == category,
                onClick = { onSelectCategory(category) },
            )
        }

        HeadingItem(text = "Options")

        val yearOptions = remember(state.availableYears) {
            listOf("Any") + state.availableYears.map { it.toString() }
        }
        val selectedYearIndex = remember(state.selectedYear, state.availableYears) {
            if (state.selectedYear != null) {
                val idx = state.availableYears.indexOf(state.selectedYear)
                if (idx >= 0) idx + 1 else 0
            } else {
                0
            }
        }
        SelectItem(
            label = "Year",
            options = yearOptions.toTypedArray(),
            selectedIndex = selectedYearIndex,
            onSelect = { index ->
                val year = if (index == 0) null else state.availableYears.getOrNull(index - 1)
                onSelectYear(year)
            },
        )

        CheckboxItem(
            label = "Include 18+ Content",
            checked = state.includeAdult,
            onClick = { onSetIncludeAdult(!state.includeAdult) },
        )

        val refreshOptions = remember {
            AnnouncementAutoRefresh.entries.map { it.displayName() }
        }
        val selectedRefreshIndex = remember(state.autoRefresh) {
            AnnouncementAutoRefresh.entries.indexOf(state.autoRefresh).coerceAtLeast(0)
        }
        SelectItem(
            label = "Auto Refresh Interval",
            options = refreshOptions.toTypedArray(),
            selectedIndex = selectedRefreshIndex,
            onSelect = { index ->
                onSetAutoRefresh(AnnouncementAutoRefresh.entries[index])
            },
        )
    }

    @Composable
    private fun SortPage(
        state: AnnouncementsScreenModel.State.Success,
        onSelectSort: (AnnouncementSort) -> Unit,
    ) {
        HeadingItem(text = "Sort by")
        AnnouncementSort.entries.forEach { sort ->
            RadioItem(
                label = sort.displayName(),
                selected = state.sort == sort,
                onClick = { onSelectSort(sort) },
            )
        }
    }

    private fun AnnouncementCategory.displayName(): String = when (this) {
        AnnouncementCategory.NEW_SEASON -> "New Season"
        AnnouncementCategory.ADAPTATION -> "Adaptation"
        AnnouncementCategory.SPIN_OFF -> "Spin-off"
        AnnouncementCategory.REMAKE -> "Remake"
        AnnouncementCategory.ORIGINAL -> "Original"
        AnnouncementCategory.MOVIE -> "Movie"
        AnnouncementCategory.SPECIAL -> "Special / OVA / ONA"
    }

    private fun AnnouncementSort.displayName(): String = when (this) {
        AnnouncementSort.AIRING_SOON -> "Airing Soon"
        AnnouncementSort.LATEST_ADDED -> "Latest Added"
        AnnouncementSort.POPULARITY -> "Popularity"
        AnnouncementSort.SCORE -> "Score"
        AnnouncementSort.TITLE -> "Title"
    }

    private fun AnnouncementAutoRefresh.displayName(): String = when (this) {
        AnnouncementAutoRefresh.OFF -> "Off"
        AnnouncementAutoRefresh.SIX_HOURS -> "6h"
        AnnouncementAutoRefresh.TWELVE_HOURS -> "12h"
        AnnouncementAutoRefresh.ONE_DAY -> "24h"
        AnnouncementAutoRefresh.TWO_DAYS -> "2d"
        AnnouncementAutoRefresh.FIVE_DAYS -> "5d"
        AnnouncementAutoRefresh.SEVEN_DAYS -> "7d"
    }
}