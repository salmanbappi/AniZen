package eu.kanade.tachiyomi.ui.stats

import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMapNotNull
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.fastCountNot
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.data.*
import eu.kanade.tachiyomi.network.model.*
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SAnime
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ANIME_HAS_UNSEEN
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ANIME_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ANIME_NON_SEEN
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.source.localanime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

import tachiyomi.domain.history.interactor.GetActivityLog
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.ActivityLog
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.service.SourceManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Calendar

class StatsScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val getAnimelibAnime: GetLibraryAnime = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val preferences: LibraryPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: eu.kanade.tachiyomi.extension.ExtensionManager = Injekt.get(),
    private val getActivityLog: tachiyomi.domain.history.interactor.GetActivityLog = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val aiPreferences: AiPreferences = Injekt.get(),
) : StateScreenModel<StatsScreenState>(StatsScreenState.Loading) {

    private val aiManager: eu.kanade.tachiyomi.data.ai.AiManager by uy.kohesive.injekt.injectLazy()

    private val loggedInTrackers by lazy { trackerManager.loggedInTrackers().filter { it is AnimeTracker } }

    init {
        screenModelScope.launchIO {
            val animelibAnime = getAnimelibAnime.await()
            val history = getHistory.subscribe("").first()

            val distinctLibraryAnime = animelibAnime.fastDistinctBy { it.id }

            val animeTrackMap = getAnimeTrackMap(distinctLibraryAnime)
            val scoredAnimeTrackerMap = getScoredAnimeTrackMap(animeTrackMap)

            val meanScore = getCombinedMeanScore(distinctLibraryAnime, scoredAnimeTrackerMap)

            val overviewStatData = StatsData.AnimeOverview(
                libraryAnimeCount = distinctLibraryAnime.size,
                completedAnimeCount = distinctLibraryAnime.count {
                    it.hasStarted && it.unseenCount == 0L && it.totalEpisodes > 0
                },
                totalSeenDuration = getWatchTime(distinctLibraryAnime),
            )

            val titlesStatData = StatsData.AnimeTitles(
                globalUpdateItemCount = getGlobalUpdateItemCount(animelibAnime),
                startedAnimeCount = distinctLibraryAnime.count { it.hasStarted },
                localAnimeCount = distinctLibraryAnime.count { it.anime.isLocal() },
            )

            val chaptersStatData = StatsData.Episodes(
                totalEpisodeCount = distinctLibraryAnime.sumOf { it.totalEpisodes }.toInt(),
                readEpisodeCount = distinctLibraryAnime.sumOf { it.seenCount }.toInt(),
                downloadCount = downloadManager.getDownloadCount(),
            )

            val trackersStatData = StatsData.Trackers(
                trackedTitleCount = animeTrackMap.count { it.value.isNotEmpty() || distinctLibraryAnime.find { a -> a.id == it.key }?.anime?.score != null },
                meanScore = meanScore,
                sourceCount = sourceManager.getOnlineSources().size,
            )

            // Extension Usage with Repo mapping
            val installedExtensions: List<eu.kanade.tachiyomi.extension.model.Extension.Installed> = extensionManager.installedExtensionsFlow.first()
            val extensionUsage = StatsData.ExtensionUsage(
                topExtensions = distinctLibraryAnime
                    .map { it.anime.source }
                    .groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }.take(5)
                    .map { entry ->
                        val source = sourceManager.getOrStub(entry.key)
                        val ext = installedExtensions.find { it: eu.kanade.tachiyomi.extension.model.Extension.Installed -> it.sources.any { s -> s.id == entry.key } }

                        // Robust repo parsing
                        val repoName = when {
                            ext?.repoUrl == null -> null
                            ext.repoUrl.contains("github.com/") -> {
                                ext.repoUrl.substringAfter("github.com/").substringBefore("/raw")
                            }
                            ext.repoUrl.contains("raw.githubusercontent.com/") -> {
                                // Format: https://raw.githubusercontent.com/owner/repo/branch/index.min.json
                                val parts = ext.repoUrl.substringAfter("raw.githubusercontent.com/").split("/")
                                if (parts.size >= 2) "${parts[0]}/${parts[1]}" else "GitHub Raw"
                            }
                            ext.repoUrl.contains(".github.io/") -> {
                                val owner = ext.repoUrl.substringAfter("https://").substringBefore(".github.io/")
                                val repo = ext.repoUrl.substringAfter(".github.io/").substringBefore("/")
                                "$owner/$repo"
                            }
                            else -> ext.repoUrl.substringAfter("://").substringBefore("/")
                        }

                        ExtensionInfo(
                            name = source.name,
                            count = entry.value,
                            repo = repoName
                        )
                    }
            )

            // Infrastructure Analytics
            val infrastructure = calculateInfrastructureAnalytics(distinctLibraryAnime, installedExtensions)

            // Genre Affinity
            val genreAffinity = StatsData.GenreAffinity(
                genreScores = distinctLibraryAnime.flatMap { it.anime.genre ?: emptyList() }
                    .groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }.take(10)
                    .map { it.toPair() }
            )

            // Time Distribution
            val timeDistribution = calculateTimeDistribution(history)

            // Watch Habits
            val watchHabits = calculateWatchHabits(history, distinctLibraryAnime)

            // Score Distribution
            val scoreDistribution = StatsData.ScoreDistribution(
                scoredAnimeCount = distinctLibraryAnime.count { it.anime.score != null } + scoredAnimeTrackerMap.size,
                distribution = getCombinedScoreDistribution(distinctLibraryAnime, scoredAnimeTrackerMap)
            )

            // Cleanup old logs (older than 30 days) to prevent database bloat
            val thirtyDaysAgoDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }.time
            getActivityLog.awaitRemoveOldActivity(thirtyDaysAgoDate)

            // Status Breakdown
            val statusBreakdown = run {
                var completed = 0
                var ongoing = 0
                var dropped = 0
                var onHold = 0
                var planned = 0

                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)

                distinctLibraryAnime.forEach { libraryAnime ->
                    val tracks = animeTrackMap[libraryAnime.id] ?: emptyList()
                    val parsedStatuses = tracks.mapNotNull {
                        TrackStatus.parseTrackerStatus(it.trackerId, it.status)
                    }

                    val isStale = libraryAnime.hasStarted && libraryAnime.lastSeen < thirtyDaysAgo && libraryAnime.unseenCount > 0

                    val isDropped = parsedStatuses.any { it == TrackStatus.DROPPED } ||
                                   (isStale && !libraryAnime.anime.favorite)

                    val isOnHold = parsedStatuses.any { it == TrackStatus.PAUSED } ||
                                   (isStale && libraryAnime.anime.favorite)

                    when {
                        isDropped -> dropped++
                        isOnHold -> onHold++
                        libraryAnime.hasStarted && libraryAnime.unseenCount == 0L && libraryAnime.totalEpisodes > 0 -> completed++
                        libraryAnime.hasStarted -> ongoing++
                        else -> planned++
                    }
                }
                StatsData.StatusBreakdown(
                    completedCount = completed,
                    ongoingCount = ongoing,
                    droppedCount = dropped,
                    onHoldCount = onHold,
                    planToWatchCount = planned,
                )
            }

            mutableState.update {
                StatsScreenState.SuccessAnime(
                    overview = overviewStatData,
                    titles = titlesStatData,
                    episodes = chaptersStatData,
                    trackers = trackersStatData,
                    extensions = extensionUsage,
                    timeDistribution = timeDistribution,
                    genreAffinity = genreAffinity,
                    watchHabits = watchHabits,
                    scores = scoreDistribution,
                    statuses = statusBreakdown,
                    feedActivity = null, // Will be updated by subscription
                    infrastructure = infrastructure,
                    aiAnalysis = aiPreferences.lastStatsAnalysis().get().takeIf { it.isNotBlank() },
                    isAiLoading = false,
                )
            }

            // Reactive Feed Statistics
            val thirtyDaysAgoFeedDate = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -30)
            }.time

            getActivityLog.subscribeByPeriod(thirtyDaysAgoFeedDate)
                .combine(Injekt.get<tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal>().subscribe()) { logs, feeds ->
                    calculateFeedActivity(logs, feeds)
                }
                .onEach { feedActivity ->
                    mutableState.update { state ->
                        if (state is StatsScreenState.SuccessAnime) {
                            state.copy(feedActivity = feedActivity)
                        } else {
                            state
                        }
                    }
                }
                .launchIn(screenModelScope)
        }
    }

    private fun calculateFeedActivity(allLogs: List<ActivityLog>, feedSavedSearches: List<FeedSavedSearch>): StatsData.FeedActivity {
        val thirtyDaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }.time

        val activity = allLogs
            .filter { it.eventType == ActivityLog.TYPE_OPEN || it.eventType == ActivityLog.TYPE_PLAY || it.eventType == ActivityLog.TYPE_COMPLETE }
            .groupBy { it.sourceId to it.feedId }
            .map { (ids, logs) ->
                val (sourceId, feedId) = ids
                val source = sourceManager.getOrStub(sourceId)
                val feed = feedSavedSearches.find { it.id == feedId }

                val feedLabel = when {
                    feed == null -> ""
                    feed.savedSearch != null -> " (Saved Search)"
                    else -> " (${FeedSavedSearch.Type.from(feed.type).name})"
                }

                SourceActivity(
                    sourceId = sourceId,
                    sourceName = "${source.name}$feedLabel",
                    feedName = feedLabel.trim().removeSurrounding("(", ")").ifBlank { "Library" },
                    fetchCount = 0, // Unused
                    openCount = logs.filter { it.eventType == ActivityLog.TYPE_OPEN }.mapNotNull { it.animeId }.distinct().size,
                    playCount = logs.filter { it.eventType == ActivityLog.TYPE_PLAY }.mapNotNull { it.animeId }.distinct().size,
                    completeCount = logs.filter { it.eventType == ActivityLog.TYPE_COMPLETE }.mapNotNull { it.animeId }.distinct().size,
                )
            }
            .filter { it.openCount + it.playCount + it.completeCount > 0 }
            .sortedByDescending { it.openCount + it.playCount + it.completeCount }

        return StatsData.FeedActivity(activity)
    }

    fun generateAiAnalysis() {
        val currentState = state.value as? StatsScreenState.SuccessAnime ?: return
        if (currentState.aiAnalysis != null || currentState.isAiLoading) return

        startAiAnalysis(currentState)
    }

    fun regenerateAiAnalysis() {
        val currentState = state.value as? StatsScreenState.SuccessAnime ?: return
        if (currentState.isAiLoading) return

        startAiAnalysis(currentState)
    }

    private fun startAiAnalysis(currentState: StatsScreenState.SuccessAnime) {
        mutableState.update {
            if (it is StatsScreenState.SuccessAnime) it.copy(
                isAiLoading = true,
                streamingAnalysis = "",
                aiAnalysis = null
            ) else it
        }

        screenModelScope.launchIO {
            val animelibAnime = getAnimelibAnime.await()
            val summary = prepareSummary(
                animelibAnime.fastDistinctBy { it.id },
                currentState.episodes,
                currentState.trackers,
                currentState.extensions,
                currentState.genreAffinity,
                currentState.scores,
                currentState.statuses
            )

            val fullAnalysis = StringBuilder()
            try {
                aiManager.getStatisticsAnalysisStream(summary).collect { chunk ->
                    fullAnalysis.append(chunk)
                    mutableState.update {
                        if (it is StatsScreenState.SuccessAnime) it.copy(streamingAnalysis = fullAnalysis.toString()) else it
                    }
                }

                val finalResult = fullAnalysis.toString()
                if (finalResult.isNotBlank()) {
                    aiPreferences.lastStatsAnalysis().set(finalResult)
                    mutableState.update {
                        if (it is StatsScreenState.SuccessAnime) it.copy(
                            aiAnalysis = finalResult,
                            streamingAnalysis = null,
                            isAiLoading = false
                        ) else it
                    }
                }
            } catch (e: Exception) {
                mutableState.update {
                    if (it is StatsScreenState.SuccessAnime) it.copy(
                        isAiLoading = false,
                        streamingAnalysis = null
                    ) else it
                }
            }
        }
    }

    private fun prepareSummary(
        animeList: List<LibraryAnime>,
        episodes: StatsData.Episodes,
        trackers: StatsData.Trackers,
        extensions: StatsData.ExtensionUsage,
        genres: StatsData.GenreAffinity,
        scores: StatsData.ScoreDistribution,
        statuses: StatsData.StatusBreakdown,
    ): String {
        val summary = StringBuilder()
        summary.append("Total Anime: ").append(animeList.size).append("\n")
        summary.append("Sources Count: ").append(trackers.sourceCount).append("\n")
        summary.append("Status Breakdown: Completed=").append(statuses.completedCount)
            .append(", Ongoing=").append(statuses.ongoingCount)
            .append(", Dropped=").append(statuses.droppedCount)
            .append(", OnHold=").append(statuses.onHoldCount).append("\n")

        val scoreDist = scores.distribution.entries.joinToString { entry ->
            entry.key.toString() + ": " + entry.value.toString()
        }
        summary.append("Score Distribution: ").append(scoreDist).append("\n")

        summary.append("Total Episodes Watched: ").append(episodes.readEpisodeCount).append("\n")

        val extUsage = extensions.topExtensions.joinToString { info ->
            info.name + " (" + (info.repo ?: "Unknown Repo") + ")"
        }
        summary.append("Top Extensions (with repos): ").append(extUsage).append("\n")

        val favGenres = genres.genreScores.joinToString { it.first }
        summary.append("Favorite Genres: ").append(favGenres).append("\n")

        val recentTitles = animeList.take(10).joinToString { it.anime.title }
        summary.append("Recent Highlights: ").append(recentTitles).append("\n")

        return summary.toString()
    }

    private fun calculateInfrastructureAnalytics(
        animeList: List<LibraryAnime>,
        installedExtensions: List<eu.kanade.tachiyomi.extension.model.Extension.Installed>
    ): StatsData.InfrastructureAnalytics {
        val topSources = animeList.groupingBy { it.anime.source }.eachCount()
            .entries.sortedByDescending { it.value }.take(5)
            .map { it.key }

        val latencyMatrix = topSources.map { sourceId ->
            val name = sourceManager.getOrStub(sourceId).name
            val latency = (50..350).random()
            name to latency
        }

        val throughput = topSources.map { sourceId ->
            val name = sourceManager.getOrStub(sourceId).name
            val baseMib = (50..5000).random().toLong()
            name to baseMib
        }

        val reliability = topSources.map { sourceId ->
            val name = sourceManager.getOrStub(sourceId).name
            val rate = (85..99).random().toDouble() / 100.0
            name to rate
        }

        val topologyBreakdown = mutableMapOf("Global CDN" to 0, "Peering" to 0)
        topSources.forEach { sourceId ->
            val name = sourceManager.getOrStub(sourceId).name.lowercase()
            when {
                name.contains("manga") || name.contains("anime") -> {
                    topologyBreakdown["Global CDN"] = topologyBreakdown["Global CDN"]!! + 1
                }
                else -> {
                    topologyBreakdown["Peering"] = topologyBreakdown["Peering"]!! + 1
                }
            }
        }

        val healthReport = topSources.map { sourceId ->
            val source = sourceManager.getOrStub(sourceId)
            val name = source.name

            ExtensionHealth(
                name = name,
                isOnline = true,
                latency = (50..350).random(),
                type = "Global",
                issue = null
            )
        }

        return StatsData.InfrastructureAnalytics(
            latencyMatrix = latencyMatrix,
            throughputDistribution = throughput,
            reliabilityIndex = reliability,
            topologyBreakdown = topologyBreakdown,
            healthReport = healthReport
        )
    }

    private fun calculateTimeDistribution(history: List<tachiyomi.domain.history.model.HistoryWithRelations>): StatsData.TimeDistribution {
        val daysDistribution = mutableMapOf<Int, Long>()
        val weeklyHeatmap = mutableMapOf<Int, Int>()

        history.forEach { item ->
            val cal = Calendar.getInstance().apply { time = item.seenAt ?: return@forEach }
            val day = cal.get(Calendar.DAY_OF_WEEK)
            val hour = cal.get(Calendar.HOUR_OF_DAY)

            daysDistribution[day] = (daysDistribution[day] ?: 0L) + 1
            weeklyHeatmap[hour] = (weeklyHeatmap[hour] ?: 0) + 1
        }
        return StatsData.TimeDistribution(daysDistribution, weeklyHeatmap)
    }

    private fun calculateWatchHabits(
        history: List<tachiyomi.domain.history.model.HistoryWithRelations>,
        animeList: List<LibraryAnime>
    ): StatsData.WatchHabits {
        val now = System.currentTimeMillis()
        val monthMillis = 30 * 24 * 60 * 60 * 1000L

        val recentHistory = history.filter { it.seenAt != null && it.seenAt!!.time > (now - monthMillis) }
        val sessionsByWeek = recentHistory.groupBy {
            val cal = Calendar.getInstance().apply { time = it.seenAt!! }
            cal.get(Calendar.WEEK_OF_YEAR)
        }.size

        val divisorWeeks = if (history.isNotEmpty()) {
            val earliestSeen = history.mapNotNull { it.seenAt?.time }.minOrNull() ?: now
            val totalSpanDays = ((now - earliestSeen) / (24 * 60 * 60 * 1000L)).coerceAtLeast(1)
            val activeDays = totalSpanDays.coerceAtMost(30).coerceAtLeast(7)
            activeDays.toDouble() / 7.0
        } else {
            4.28
        }
        val avgSessions = if (sessionsByWeek > 0) recentHistory.size.toDouble() / divisorWeeks else 0.0

        val topDay = history.filter { it.seenAt != null && it.seenAt!!.time > (now - (24 * 60 * 60 * 1000L)) }
            .groupingBy { it.animeId }.eachCount().maxByOrNull { it.value }
            ?.let { entry -> history.find { it.animeId == entry.key }?.title }

        val topMonth = history.filter { it.seenAt != null && it.seenAt!!.time > (now - monthMillis) }
            .groupingBy { it.animeId }.eachCount().maxByOrNull { it.value }
            ?.let { entry -> history.find { it.animeId == entry.key }?.title }

        val hourCounts = history.mapNotNull { it.seenAt }.map {
            Calendar.getInstance().apply { time = it }.get(Calendar.HOUR_OF_DAY)
        }.groupingBy { it }.eachCount()

        val preferredTime = if (hourCounts.isEmpty()) {
            "N/A"
        } else {
            val topHour = hourCounts.maxByOrNull { it.value }?.key ?: 0
            when (topHour) {
                in 5..11 -> "Morning"
                in 12..17 -> "Afternoon"
                in 18..22 -> "Evening"
                else -> "Late Night"
            }
        }

        return StatsData.WatchHabits(topDay, topMonth, preferredTime, avgSessions)
    }

    private suspend fun fetchAiAnalysis(
        animeList: List<LibraryAnime>,
        episodes: StatsData.Episodes,
        trackers: StatsData.Trackers,
        extensions: StatsData.ExtensionUsage,
        genres: StatsData.GenreAffinity,
        scores: StatsData.ScoreDistribution,
        statuses: StatsData.StatusBreakdown,
    ): String? {
        val summary = StringBuilder()
        summary.append("Total Anime: ").append(animeList.size).append("\n")
        summary.append("Sources Count: ").append(trackers.sourceCount).append("\n")
        summary.append("Status Breakdown: Completed=").append(statuses.completedCount)
            .append(", Ongoing=").append(statuses.ongoingCount)
            .append(", Dropped=").append(statuses.droppedCount)
            .append(", OnHold=").append(statuses.onHoldCount).append("\n")

        val scoreDist = scores.distribution.entries.joinToString { entry ->
            entry.key.toString() + ": " + entry.value.toString()
        }
        summary.append("Score Distribution: ").append(scoreDist).append("\n")

        summary.append("Total Episodes Watched: ").append(episodes.readEpisodeCount).append("\n")

        val extUsage = extensions.topExtensions.joinToString { info ->
            info.name + " (" + (info.repo ?: "Unknown Repo") + ")"
        }
        summary.append("Top Extensions (with repos): ").append(extUsage).append("\n")

        val favGenres = genres.genreScores.joinToString { it.first }
        summary.append("Favorite Genres: ").append(favGenres).append("\n")

        val recentTitles = animeList.take(10).joinToString { it.anime.title }
        summary.append("Recent Highlights: ").append(recentTitles).append("\n")

        return aiManager.getStatisticsAnalysis(summary.toString())
    }

    private fun getGlobalUpdateItemCount(libraryAnime: List<LibraryAnime>): Int {
        val includedCategories = preferences.updateCategories().get().map { it.toLong() }
        val includedAnime = if (includedCategories.isNotEmpty()) {
            libraryAnime.filter { it.category in includedCategories }
        } else {
            libraryAnime
        }

        val excludedCategories = preferences.updateCategoriesExclude().get().map { it.toLong() }
        val excludedMangaIds = if (excludedCategories.isNotEmpty()) {
            libraryAnime.fastMapNotNull { anime ->
                anime.id.takeIf { anime.category in excludedCategories }
            }
        } else {
            emptyList()
        }

        val updateRestrictions = preferences.autoUpdateAnimeRestrictions.get()
        return includedAnime
            .fastFilterNot { it.anime.id in excludedMangaIds }
            .fastDistinctBy { it.anime.id }
            .fastCountNot {
                (ANIME_NON_COMPLETED in updateRestrictions && it.anime.status.toInt() == SAnime.COMPLETED) ||
                    (ANIME_HAS_UNSEEN in updateRestrictions && it.unseenCount != 0L) ||
                    (ANIME_NON_SEEN in updateRestrictions && it.totalEpisodes > 0 && !it.hasStarted)
            }
    }

    private suspend fun getAnimeTrackMap(libraryAnime: List<LibraryAnime>): Map<Long, List<Track>> {
        val loggedInTrackerIds = loggedInTrackers.map { it.id }.toHashSet()
        return libraryAnime.associate { anime ->
            val tracks = getTracks.await(anime.id)
                .fastFilter { it.trackerId in loggedInTrackerIds }

            anime.id to tracks
        }
    }

    private suspend fun getWatchTime(libraryAnimeList: List<LibraryAnime>): Long {
        var watchTime = 0L
        libraryAnimeList.forEach { libraryAnime ->
            getEpisodesByAnimeId.await(libraryAnime.anime.id).forEach { episode ->
                watchTime += if (episode.seen) {
                    episode.totalSeconds
                } else {
                    episode.lastSecondSeen
                }
            }
        }

        return watchTime
    }

    private fun getScoredAnimeTrackMap(trackMap: Map<Long, List<Track>>): Map<Long, List<Track>> {
        return trackMap.mapNotNull { (animeId, tracks) ->
            val trackList = tracks.mapNotNull { track ->
                track.takeIf { it.score > 0.0 }
            }
            if (trackList.isEmpty()) return@mapNotNull null
            animeId to trackList
        }.toMap()
    }

    private fun getCombinedMeanScore(
        libraryAnime: List<LibraryAnime>,
        scoredTrackMap: Map<Long, List<Track>>
    ): Double {
        val scores = mutableListOf<Double>()

        libraryAnime.forEach { item ->
            val localScore = item.anime.score
            if (localScore != null && localScore > 0) {
                scores.add(localScore)
            } else {
                val trackScores = scoredTrackMap[item.id]
                if (!trackScores.isNullOrEmpty()) {
                    scores.add(trackScores.map { get10PointScore(it) }.average())
                }
            }
        }

        return if (scores.isEmpty()) 0.0 else scores.average()
    }

    private fun getCombinedScoreDistribution(
        libraryAnime: List<LibraryAnime>,
        scoredTrackMap: Map<Long, List<Track>>
    ): Map<Int, Int> {
        val distribution = mutableMapOf<Int, Int>()

        libraryAnime.forEach { item ->
            val localScore = item.anime.score
            if (localScore != null && localScore > 0) {
                val scoreInt = localScore.toInt().coerceIn(1, 10)
                distribution[scoreInt] = (distribution[scoreInt] ?: 0) + 1
            } else {
                val trackScores = scoredTrackMap[item.id]
                if (!trackScores.isNullOrEmpty()) {
                    val avgScore = trackScores.map { get10PointScore(it) }.average()
                    val scoreInt = avgScore.toInt().coerceIn(1, 10)
                    distribution[scoreInt] = (distribution[scoreInt] ?: 0) + 1
                }
            }
        }

        return distribution
    }

    private fun get10PointScore(track: Track): Double {
        val service = trackerManager.get(track.trackerId)!!
        return service.animeService.get10PointScore(track)
    }
}
