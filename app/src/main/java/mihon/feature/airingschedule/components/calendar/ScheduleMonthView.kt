package mihon.feature.airingschedule.components.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mihon.core.designsystem.utils.isExpandedWidthWindow
import mihon.feature.airingschedule.AiringScheduleEntry
import mihon.feature.airingschedule.SchedulePreferences
import mihon.feature.airingschedule.components.BellNotifyState
import mihon.feature.airingschedule.components.ScheduleAnimeCard
import mihon.feature.airingschedule.util.ScheduleTitleMatcher
import tachiyomi.presentation.core.components.material.padding
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ScheduleMonthView(
    entries: List<AiringScheduleEntry>,
    titleLanguage: SchedulePreferences.TitleLanguage = SchedulePreferences.TitleLanguage.USER_PREFERRED,
    sourceDelays: Map<String, Long> = emptyMap(),
    manualDelayMinutes: Long? = null,
    favoriteSourceIds: Set<String> = emptySet(),
    pinnedSources: Set<String> = emptySet(),
    libraryAnimeTitles: Set<String> = emptySet(),
    libraryAnimeIdByTitle: Map<String, Long> = emptyMap(),
    librarySourcesByTitle: Map<String, Set<String>> = emptyMap(),
    onceMediaIds: Set<String> = emptySet(),
    seriesMediaIds: Set<String> = emptySet(),
    onToggleAlert: (AiringScheduleEntry) -> Unit,
    onLongClickAlert: (AiringScheduleEntry) -> Unit,
    onOpenAnime: (Long) -> Unit,
    onSearchAnime: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    var selectedYearMonth by remember { mutableStateOf(YearMonth.now(zone)) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(LocalDate.now(zone)) }
    val listState = rememberLazyListState()

    // Map all entries by their LocalDate
    val entriesByDate = remember(entries) {
        entries.groupBy { entry ->
            Instant.ofEpochSecond(entry.airingAt).atZone(zone).toLocalDate()
        }
    }

    // Events count per date for calendar indicators
    val eventsByDate = remember(entriesByDate) {
        entriesByDate.mapValues { it.value.size }
    }

    // Filter entries for the selected month or selected date
    val displayDates = remember(entriesByDate, selectedYearMonth, selectedDate) {
        if (selectedDate != null && selectedDate?.year == selectedYearMonth.year && selectedDate?.month == selectedYearMonth.month) {
            listOfNotNull(selectedDate).filter { entriesByDate.containsKey(it) }
        } else {
            entriesByDate.keys.filter { it.year == selectedYearMonth.year && it.month == selectedYearMonth.month }
                .sorted()
        }
    }

    if (isExpandedWidthWindow()) {
        Row(
            modifier = modifier.fillMaxSize().padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Calendar(
                    selectedYearMonth = selectedYearMonth,
                    events = eventsByDate,
                    selectedDate = selectedDate,
                    setSelectedYearMonth = {
                        selectedYearMonth = it
                        selectedDate = null
                    },
                    onClickDay = { date ->
                        selectedDate = if (selectedDate == date) null else date
                    },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = MaterialTheme.padding.small),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (displayDates.isEmpty()) {
                    item {
                        Text(
                            text = "No airing episodes scheduled for this period",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                displayDates.forEach { date ->
                    val dayEntries = entriesByDate[date].orEmpty().sortedBy { it.airingAt }
                    item(key = "header_${date}") {
                        MonthDateHeader(date = date, count = dayEntries.size)
                    }
                    items(
                        items = dayEntries,
                        key = { "entry_${it.scheduleId}" },
                    ) { entry ->
                        val bellState = when {
                            entry.mediaId.toString() in seriesMediaIds -> BellNotifyState.SERIES
                            entry.mediaId.toString() in onceMediaIds -> BellNotifyState.ONCE
                            else -> BellNotifyState.NONE
                        }
                        val matchedAnimeId = remember(entry.scheduleId, libraryAnimeIdByTitle) {
                            val candidates = ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
                            val candidateKeys = candidates.flatMap { ScheduleTitleMatcher.normalizedKeys(it) }
                            candidateKeys.firstNotNullOfOrNull { libraryAnimeIdByTitle[it] }
                        }
                        val isInLibrary = matchedAnimeId != null || remember(entry.scheduleId, libraryAnimeTitles) {
                            val candidates = ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
                            val candidateKeys = candidates.flatMap { ScheduleTitleMatcher.normalizedKeys(it) }
                            candidateKeys.any { it in libraryAnimeTitles }
                        }
                        ScheduleAnimeCard(
                            entry = entry,
                            titleLanguage = titleLanguage,
                            sourceDelays = sourceDelays,
                            manualDelayMinutes = manualDelayMinutes,
                            favoriteSourceIds = favoriteSourceIds,
                            pinnedSourceIds = pinnedSources,
                            isInLibrary = isInLibrary,
                            notifyState = bellState,
                            onSearchClick = {
                                if (matchedAnimeId != null) onOpenAnime(matchedAnimeId)
                                else onSearchAnime(it)
                            },
                            onAddToLibraryClick = { onSearchAnime(it) },
                            onToggleNotifyOnce = { onToggleAlert(entry) },
                            onToggleNotifySeries = { onLongClickAlert(entry) },
                        )
                    }
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "calendar_header_widget") {
                Calendar(
                    selectedYearMonth = selectedYearMonth,
                    events = eventsByDate,
                    selectedDate = selectedDate,
                    setSelectedYearMonth = {
                        selectedYearMonth = it
                        selectedDate = null
                    },
                    onClickDay = { date ->
                        selectedDate = if (selectedDate == date) null else date
                    },
                )
            }
            if (displayDates.isEmpty()) {
                item {
                    Text(
                        text = "No airing episodes scheduled for this date",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            displayDates.forEach { date ->
                val dayEntries = entriesByDate[date].orEmpty().sortedBy { it.airingAt }
                item(key = "header_${date}") {
                    MonthDateHeader(date = date, count = dayEntries.size)
                }
                items(
                    items = dayEntries,
                    key = { "entry_${it.scheduleId}" },
                ) { entry ->
                    val bellState = when {
                        entry.mediaId.toString() in seriesMediaIds -> BellNotifyState.SERIES
                        entry.mediaId.toString() in onceMediaIds -> BellNotifyState.ONCE
                        else -> BellNotifyState.NONE
                    }
                    val matchedAnimeId = remember(entry.scheduleId, libraryAnimeIdByTitle) {
                        val candidates = ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
                        val candidateKeys = candidates.flatMap { ScheduleTitleMatcher.normalizedKeys(it) }
                        candidateKeys.firstNotNullOfOrNull { libraryAnimeIdByTitle[it] }
                    }
                    val isInLibrary = matchedAnimeId != null || remember(entry.scheduleId, libraryAnimeTitles) {
                        val candidates = ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
                        val candidateKeys = candidates.flatMap { ScheduleTitleMatcher.normalizedKeys(it) }
                        candidateKeys.any { it in libraryAnimeTitles }
                    }
                    ScheduleAnimeCard(
                        entry = entry,
                        titleLanguage = titleLanguage,
                        sourceDelays = sourceDelays,
                        manualDelayMinutes = manualDelayMinutes,
                        favoriteSourceIds = favoriteSourceIds,
                        pinnedSourceIds = pinnedSources,
                        isInLibrary = isInLibrary,
                        notifyState = bellState,
                        onSearchClick = {
                            if (matchedAnimeId != null) onOpenAnime(matchedAnimeId)
                            else onSearchAnime(it)
                        },
                        onAddToLibraryClick = { onSearchAnime(it) },
                        onToggleNotifyOnce = { onToggleAlert(entry) },
                        onToggleNotifySeries = { onLongClickAlert(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthDateHeader(
    date: LocalDate,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("EEEE, MMMM d") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 6.dp),
    ) {
        Text(
            text = date.format(formatter),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Badge(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Text("$count")
        }
    }
}
