package mihon.feature.announcements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
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

    @OptIn(ExperimentalLayoutApi::class)
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
        AdaptiveSheet(onDismissRequest = onDismissRequest) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(scrollState),
            ) {
                // Header row with Title and Reset button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.action_filter),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = onReset) {
                        Text(text = stringResource(MR.strings.action_reset))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Category Section
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = state.selectedCategory == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text("All") },
                    )
                    AnnouncementCategory.entries.forEach { category ->
                        FilterChip(
                            selected = state.selectedCategory == category,
                            onClick = { onSelectCategory(category) },
                            label = { Text(category.displayName()) },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Sort Section (integrated directly inside Filters)
                Text(
                    text = stringResource(MR.strings.action_sort),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AnnouncementSort.entries.forEach { sort ->
                        FilterChip(
                            selected = state.sort == sort,
                            onClick = { onSelectSort(sort) },
                            label = { Text(sort.displayName()) },
                        )
                    }
                }

                if (state.availableYears.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Release Year Section
                    Text(
                        text = "Release Year",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilterChip(
                            selected = state.selectedYear == null,
                            onClick = { onSelectYear(null) },
                            label = { Text("Any") },
                        )
                        state.availableYears.forEach { year ->
                            FilterChip(
                                selected = state.selectedYear == year,
                                onClick = { onSelectYear(year) },
                                label = { Text(year.toString()) },
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // 18+ Content Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Include 18+ Content",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Show adult and NSFW announcements",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.includeAdult,
                        onCheckedChange = onSetIncludeAdult,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Auto Refresh Interval
                Text(
                    text = "Auto Refresh Interval",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AnnouncementAutoRefresh.entries.forEach { refresh ->
                        FilterChip(
                            selected = state.autoRefresh == refresh,
                            onClick = { onSetAutoRefresh(refresh) },
                            label = { Text(refresh.displayName()) },
                        )
                    }
                }
            }
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