package mihon.feature.airingschedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.launch
import mihon.feature.airingschedule.components.BellNotifyState
import mihon.feature.airingschedule.components.ScheduleAnimeCard
import mihon.feature.airingschedule.components.ScheduleFilterSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavItem
import eu.kanade.tachiyomi.ui.more.MoreTab
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val orderedDays = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY,
)

data object AiringScheduleTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 5u,
                title = stringResource(MR.strings.label_schedule_short),
                icon = rememberVectorPainter(Icons.Outlined.DateRange),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {}

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val bottomNavTabs by uiPreferences.bottomNavTabs().collectAsStatePref()
        val isTabInBottomBar = remember(bottomNavTabs) {
            val visibleNavItems = bottomNavTabs.mapNotNull { id -> NavItem.fromId(id) }.filter { it.tab.isEnabled() }
            visibleNavItems.any { it.tab::class == AiringScheduleTab::class }
        }
        val screenModel = rememberScreenModel { AiringScheduleScreenModel() }
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        var showFilterSheet by remember { mutableStateOf(false) }

        val todayIndex = orderedDays.indexOf(state.selectedDay).coerceAtLeast(0)
        val pagerState = rememberPagerState(initialPage = todayIndex) { orderedDays.size }

        LaunchedEffect(pagerState.currentPage) {
            screenModel.selectDay(orderedDays[pagerState.currentPage])
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (!isTabInBottomBar) {
                            IconButton(onClick = { tabNavigator.current = MoreTab }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(MR.strings.action_bar_up_description),
                                )
                            }
                        }
                    },
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = stringResource(MR.strings.label_airing_schedule),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            val weekRange = buildWeekRangeLabel(state.weekStartDate, state.weekEndDate)
                            if (weekRange.isNotEmpty()) {
                                Text(
                                    text = weekRange,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showFilterSheet = true }) {
                            Icon(
                                imageVector = Icons.Outlined.FilterList,
                                contentDescription = stringResource(MR.strings.action_filter_schedule),
                                tint = if (state.hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { screenModel.loadSchedule(forceRefresh = true) }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.cd_refresh_schedule))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { paddingValues ->
            when {
                state.isLoading && state.scheduleByDay.isEmpty() -> LoadingScreen(modifier = Modifier.padding(paddingValues))
                state.error != null && state.scheduleByDay.isEmpty() -> ScheduleErrorContent(
                    error = state.error!!,
                    onRetry = { screenModel.loadSchedule(forceRefresh = true) },
                    modifier = Modifier.padding(paddingValues),
                )
                else -> Column(modifier = Modifier.padding(paddingValues)) {
                    ScheduleDayTabRow(
                        pagerState = pagerState,
                        weekStartDate = state.weekStartDate,
                        scheduleByDay = state.scheduleByDay,
                        onTabClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    )
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { pageIndex ->
                        val day = orderedDays[pageIndex]
                        val entries = state.scheduleByDay[day] ?: emptyList()
                        ScheduleDayContent(
                            entries = entries,
                            titleLanguage = state.titleLanguage,
                            sourceDelays = state.sourceDelays,
                            manualDelayMinutes = state.manualDelayMinutes,
                            favoriteSourceIds = state.favoriteSourceIds,
                            pinnedSourceIds = state.pinnedSourceIds,
                            libraryAnimeTitles = state.libraryAnimeTitles,
                            libraryAnimeIdByTitle = state.libraryAnimeIdByTitle,
                            onOpenAnime = { animeId -> navigator.push(AnimeScreen(animeId)) },
                            onSearchClick = { title ->
                                navigator.push(
                                    GlobalSearchScreen(
                                        searchQuery = title,
                                    ),
                                )
                            },
                            onAddToLibraryClick = { title ->
                                navigator.push(
                                    GlobalSearchScreen(
                                        searchQuery = title,
                                    ),
                                )
                            },
                            notifyOnceMediaIds = state.notifyOnceMediaIds,
                            notifySeriesMediaIds = state.notifySeriesMediaIds,
                            onToggleNotifyOnce = { entry -> screenModel.toggleNotifyOnce(entry) },
                            onToggleNotifySeries = { entry -> screenModel.toggleNotifySeries(entry) },
                        )
                    }
                }
            }
        }

        if (showFilterSheet) {
            ScheduleFilterSheet(
                onDismissRequest = { showFilterSheet = false },
                onlyFavorites = state.onlyFavorites,
                onToggleOnlyFavorites = screenModel::setFilterOnlyFavorites,
                hideAired = state.hideAired,
                onToggleHideAired = screenModel::setFilterHideAired,
                showAdult = state.showAdult,
                onToggleShowAdult = screenModel::setFilterShowAdult,
                selectedFormats = state.selectedFormats,
                onToggleFormat = screenModel::toggleFilterFormat,
                onResetFilters = screenModel::resetFilters,
            )
        }
    }
}

private fun buildWeekRangeLabel(start: LocalDate?, end: LocalDate?): String {
    if (start == null || end == null) return ""
    val fmt = DateTimeFormatter.ofPattern("MMM d")
    return "${start.format(fmt)} – ${end.format(fmt)}"
}

@Composable
private fun ScheduleDayTabRow(
    pagerState: PagerState,
    weekStartDate: LocalDate?,
    scheduleByDay: Map<DayOfWeek, List<AiringScheduleEntry>>,
    onTabClick: (Int) -> Unit,
) {
    val today = LocalDate.now().dayOfWeek
    ScrollableTabRow(
        selectedTabIndex = pagerState.currentPage,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        edgePadding = 8.dp,
    ) {
        orderedDays.forEachIndexed { index, day ->
            val isToday = day == today
            val dayDate = weekStartDate?.plusDays(orderedDays.indexOf(day).toLong())
            val count = scheduleByDay[day]?.size ?: 0
            val dayShort = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { onTabClick(index) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = dayShort,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (isToday && pagerState.currentPage != index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        dayDate?.let { date ->
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (count > 0) {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ScheduleDayContent(
    entries: List<AiringScheduleEntry>,
    titleLanguage: SchedulePreferences.TitleLanguage,
    sourceDelays: Map<String, Long>,
    manualDelayMinutes: Long?,
    favoriteSourceIds: Set<String>,
    pinnedSourceIds: Set<String>,
    libraryAnimeTitles: Set<String>,
    libraryAnimeIdByTitle: Map<String, Long>,
    onOpenAnime: (Long) -> Unit,
    onSearchClick: (String) -> Unit,
    onAddToLibraryClick: (String) -> Unit,
    notifyOnceMediaIds: Set<String>,
    notifySeriesMediaIds: Set<String>,
    onToggleNotifyOnce: (AiringScheduleEntry) -> Unit,
    onToggleNotifySeries: (AiringScheduleEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyScreen(stringRes = tachiyomi.i18n.MR.strings.information_no_airing_today)
        return
    }

    var currentEpochSecond by remember { mutableStateOf(Instant.now().epochSecond) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            currentEpochSecond = Instant.now().epochSecond
        }
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items = entries, key = { it.scheduleId }) { entry ->
            val mediaKey = entry.mediaId.toString()
            val notifyState = when {
                mediaKey in notifySeriesMediaIds -> BellNotifyState.SERIES
                mediaKey in notifyOnceMediaIds -> BellNotifyState.ONCE
                else -> BellNotifyState.NONE
            }
            val matchedAnimeId = remember(entry.scheduleId, libraryAnimeIdByTitle) {
                listOfNotNull(
                    entry.titleUserPreferred,
                    entry.titleEnglish,
                    entry.titleRomaji,
                    entry.titleNative,
                ).firstNotNullOfOrNull { libraryAnimeIdByTitle[it.trim().lowercase()] }
            }
            val isInLibrary = matchedAnimeId != null || remember(entry.scheduleId, libraryAnimeTitles) {
                entry.titleUserPreferred.trim().lowercase() in libraryAnimeTitles ||
                    entry.titleEnglish?.trim()?.lowercase() in libraryAnimeTitles ||
                    entry.titleRomaji?.trim()?.lowercase() in libraryAnimeTitles ||
                    entry.titleNative?.trim()?.lowercase() in libraryAnimeTitles
            }
            ScheduleAnimeCard(
                entry = entry,
                titleLanguage = titleLanguage,
                sourceDelays = sourceDelays,
                manualDelayMinutes = manualDelayMinutes,
                favoriteSourceIds = favoriteSourceIds,
                pinnedSourceIds = pinnedSourceIds,
                isInLibrary = isInLibrary,
                notifyState = notifyState,
                currentTimeEpochSecond = currentEpochSecond,
                onSearchClick = { title ->
                    if (matchedAnimeId != null) {
                        onOpenAnime(matchedAnimeId)
                    } else {
                        onSearchClick(title)
                    }
                },
                onAddToLibraryClick = { title ->
                    if (matchedAnimeId != null) {
                        onOpenAnime(matchedAnimeId)
                    } else {
                        onAddToLibraryClick(title)
                    }
                },
                onToggleNotifyOnce = { onToggleNotifyOnce(entry) },
                onToggleNotifySeries = { onToggleNotifySeries(entry) },
            )
        }
    }
}

@Composable
private fun ScheduleErrorContent(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        ) {
            Text(
                text = stringResource(MR.strings.schedule_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(stringResource(MR.strings.action_retry))
            }
        }
    }
}
