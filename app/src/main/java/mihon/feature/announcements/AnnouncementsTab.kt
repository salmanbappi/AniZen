package mihon.feature.announcements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow

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
                onSelectCategory = screenModel::selectCategory,
                onSelectYear = screenModel::selectYear,
                onSelectSort = screenModel::setSort,
                onSetIncludeAdult = screenModel::setIncludeAdult,
                onSetAutoRefresh = screenModel::setAutoRefresh,
                onRefresh = { screenModel.load(forceRefresh = true) },
                onCardClick = { entry ->
                    navigator.push(AnnouncementDetailScreen(entry))
                },
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
        onSelectCategory: (AnnouncementCategory?) -> Unit,
        onSelectYear: (Int?) -> Unit,
        onSelectSort: (AnnouncementSort) -> Unit,
        onSetIncludeAdult: (Boolean) -> Unit,
        onSetAutoRefresh: (AnnouncementAutoRefresh) -> Unit,
        onRefresh: () -> Unit,
        onCardClick: (AnnouncementEntry) -> Unit,
    ) {
        var showFilters by remember { mutableStateOf(false) }
        var showSortMenu by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize()) {
            CategoryChipsRow(selected = state.selectedCategory, onSelect = onSelectCategory)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { showSortMenu = true },
                        label = { Text("Sort: ${state.sort.displayName()}") },
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        AnnouncementSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.displayName()) },
                                leadingIcon = {
                                    RadioButton(
                                        selected = state.sort == sort,
                                        onClick = null,
                                    )
                                },
                                onClick = {
                                    onSelectSort(sort)
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                }
                FilterChip(
                    selected = state.selectedYear != null || state.includeAdult,
                    onClick = { showFilters = true },
                    label = {
                        Text(
                            if (state.selectedYear == null && !state.includeAdult) {
                                "Filters"
                            } else {
                                "Filters active"
                            },
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.Tune, contentDescription = null)
                    },
                )
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    Text("Refresh", modifier = Modifier.padding(start = 4.dp))
                }
            }
            LazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.filteredEntries, key = { it.mediaId }) { entry ->
                    AnnouncementCard(entry = entry, onClick = { onCardClick(entry) })
                }
            }
        }

        if (showFilters) {
            FiltersDialog(
                state = state,
                onDismiss = { showFilters = false },
                onSelectYear = onSelectYear,
                onSetIncludeAdult = onSetIncludeAdult,
                onSetAutoRefresh = onSetAutoRefresh,
            )
        }
    }

    @Composable
    private fun CategoryChipsRow(
        selected: AnnouncementCategory?,
        onSelect: (AnnouncementCategory?) -> Unit,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    label = { Text("All") },
                )
            }
            items(AnnouncementCategory.entries.toList()) { category ->
                FilterChip(
                    selected = selected == category,
                    onClick = { onSelect(category) },
                    label = { Text(category.displayName()) },
                )
            }
        }
    }

    @Composable
    private fun FiltersDialog(
        state: AnnouncementsScreenModel.State.Success,
        onDismiss: () -> Unit,
        onSelectYear: (Int?) -> Unit,
        onSetIncludeAdult: (Boolean) -> Unit,
        onSetAutoRefresh: (AnnouncementAutoRefresh) -> Unit,
    ) {
        var showYears by remember { mutableStateOf(false) }
        var showRefreshOptions by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Filters") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Year", style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { showYears = true }) {
                            Text(state.selectedYear?.toString() ?: "Any")
                        }
                        DropdownMenu(
                            expanded = showYears,
                            onDismissRequest = { showYears = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Any") },
                                onClick = {
                                    onSelectYear(null)
                                    showYears = false
                                },
                            )
                            state.availableYears.forEach { year ->
                                DropdownMenuItem(
                                    text = { Text(year.toString()) },
                                    onClick = {
                                        onSelectYear(year)
                                        showYears = false
                                    },
                                )
                            }
                        }
                    }
                    FilterSwitchRow(
                        label = "18+",
                        checked = state.includeAdult,
                        onCheckedChange = onSetIncludeAdult,
                    )
                    Text("Auto refresh", style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { showRefreshOptions = true }) {
                            Text(state.autoRefresh.displayName())
                        }
                        DropdownMenu(
                            expanded = showRefreshOptions,
                            onDismissRequest = { showRefreshOptions = false },
                        ) {
                            AnnouncementAutoRefresh.entries.forEach { refresh ->
                                DropdownMenuItem(
                                    text = { Text(refresh.displayName()) },
                                    onClick = {
                                        onSetAutoRefresh(refresh)
                                        showRefreshOptions = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            },
        )
    }

    @Composable
    private fun FilterSwitchRow(
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
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