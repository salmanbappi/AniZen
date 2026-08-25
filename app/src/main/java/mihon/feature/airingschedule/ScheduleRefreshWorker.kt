package mihon.feature.airingschedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Monitors the Latest page of every selected favorite source.
 *
 * It is deliberately separate from the Feed tab: the worker uses the same source API call as
 * Feed, but stores only compact episode identities and timestamps in a two-day rolling journal.
 */
class ScheduleRefreshWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withIOContext {
        try {
            val schedulePrefs = Injekt.get<SchedulePreferences>()
            if (!schedulePrefs.uploadDelayEnabled().get()) return@withIOContext Result.success()

            val favoriteSourceIds = schedulePrefs.favoriteSourceIds().get()
            if (favoriteSourceIds.isEmpty()) {
                schedulePrefs.sourceFeedSyncStatus().set("No favorite sources selected")
                return@withIOContext Result.success()
            }

            val recentEntries = fetchRecentEntries(schedulePrefs)
            val result = collectSourceFeedObservations(recentEntries, favoriteSourceIds)
            val store = SourceFeedSyncStore(context)
            store.append(result.observations)
            val recentObservations = store.readRecent()

            val intervalMinutes = schedulePrefs.uploadDelayRefreshInterval().get().minutes
            val delayChanged = Injekt.get<UploadDelayTracker>().recordFeedObservations(
                recentObservations,
                intervalMinutes,
            )
            if (delayChanged) {
                ScheduleDataRefreshWorker.refreshNow(context)
            }
            schedulePrefs.lastDelayCheckTime().set(System.currentTimeMillis() / 1000L)
            schedulePrefs.lastSourceFeedSyncTime().set(System.currentTimeMillis())
            schedulePrefs.sourceFeedSyncStatus().set(
                "${result.sourcesChecked}/${favoriteSourceIds.size} source(s) checked, " +
                    "${recentObservations.size} episode observation(s) in 7-day journal" +
                    if (result.failedSources > 0) " • ${result.failedSources} unavailable" else "",
            )
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun fetchRecentEntries(
        schedulePrefs: SchedulePreferences,
    ): List<AiringScheduleEntry> {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val weekStart = now.minusDays(7)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toLocalDate().atStartOfDay(zone)
        val weekEnd = now.plusDays(1)
        val entries = AiringScheduleRepository().getWeeklySchedule(
            weekStart.toEpochSecond(),
            weekEnd.toEpochSecond(),
            includeAdult = schedulePrefs.showAdultContent().get(),
        )
        val windowStart = now.minusDays(7).toEpochSecond()
        val nowEpoch = now.toEpochSecond()
        return entries.filter { it.airingAt in windowStart..nowEpoch }
    }

    private suspend fun collectSourceFeedObservations(
        airedEntries: List<AiringScheduleEntry>,
        sourceIds: Set<String>,
    ): FeedObservationResult {
        if (airedEntries.isEmpty()) return FeedObservationResult(emptyList(), 0, 0)

        val sourceManager = Injekt.get<SourceManager>()
        val observations = mutableListOf<SourceFeedObservation>()
        var sourceCalls = 0
        var sourcesChecked = 0
        var failedSources = 0
        val nowSec = System.currentTimeMillis() / 1000L

        for (sourceId in sourceIds) {
            if (sourceCalls++ >= MAX_SOURCE_CALLS_PER_RUN) break
            val source = sourceManager.get(sourceId.toLongOrNull() ?: continue) as? AnimeCatalogueSource
                ?: continue
            val latestAnimeResult = runCatching {
                source.getLatestUpdates(1).animes.take(MAX_LATEST_ANIME_PER_SOURCE)
            }
            val latestAnime = latestAnimeResult.getOrElse {
                failedSources++
                emptyList()
            }
            if (latestAnimeResult.isSuccess) sourcesChecked++

            val matchingAnime = latestAnime.filter { anime ->
                airedEntries.any { titlesMatch(it, anime.title) }
            }.take(MAX_MATCHING_ANIME_PER_SOURCE)

            for (anime in matchingAnime) {
                val episodes = runCatching { source.getEpisodeList(anime) }.getOrNull().orEmpty()
                for (episode in episodes) {
                    if (episode.date_upload <= 0L) continue
                    if (!isSimulcastSubEpisode(episode)) continue

                    val sourceUploadAt = episode.date_upload / 1000L
                    if (sourceUploadAt > nowSec + 3600L) continue // Future timestamp sanity check

                    val entry = airedEntries.firstOrNull {
                        it.episode.toFloat() == episode.episode_number &&
                            titlesMatch(it, anime.title)
                    } ?: continue

                    val delayMinutes = (sourceUploadAt - entry.airingAt) / 60L
                    if (delayMinutes !in MIN_DELAY_MINUTES..MAX_DELAY_MINUTES) continue

                    observations += SourceFeedObservation(
                        eventId = "$sourceId|${anime.url}|${episode.url}",
                        sourceId = sourceId,
                        episodeId = episode.url,
                        episodeNumber = episode.episode_number,
                        officialAirAt = entry.airingAt,
                        sourceUploadAt = sourceUploadAt,
                    )
                }
            }
        }
        return FeedObservationResult(observations, sourcesChecked, failedSources)
    }

    private fun titlesMatch(entry: AiringScheduleEntry, sourceTitle: String): Boolean {
        val candidates = mihon.feature.airingschedule.util.ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
        return mihon.feature.airingschedule.util.ScheduleTitleMatcher.matchesAny(sourceTitle, candidates)
    }

    private fun isSimulcastSubEpisode(episode: eu.kanade.tachiyomi.animesource.model.SEpisode): Boolean {
        if (episode.episode_number <= 0f) return false
        val name = episode.name.trim()
        val scanlator = episode.scanlator?.trim().orEmpty()

        if (DUB_REGEX.containsMatchIn(name) || scanlator.contains("dub", ignoreCase = true)) return false
        if (BATCH_REGEX.containsMatchIn(name)) return false

        return true
    }

    private data class FeedObservationResult(
        val observations: List<SourceFeedObservation>,
        val sourcesChecked: Int,
        val failedSources: Int,
    )

    companion object {
        private const val WORK_NAME = "ScheduleRefreshWorker"
        private const val MAX_SOURCE_CALLS_PER_RUN = 12
        private const val MAX_LATEST_ANIME_PER_SOURCE = 50
        private const val MAX_MATCHING_ANIME_PER_SOURCE = 5
        private val MIN_DELAY_MINUTES = UploadDelayTracker.MIN_DELAY_MINUTES
        private val MAX_DELAY_MINUTES = UploadDelayTracker.MAX_DELAY_MINUTES

        private val DUB_REGEX = Regex("(?i)(\\b(eng|english|ita|ger|spa|fra)\\s+dub\\b|[\\(\\[\\{]dub[\\)\\]\\}]|\\b(dubbed)\\b|:\\s*dub\\b|-\\s*dub\\b|\\bdub\\s*$)")
        private val BATCH_REGEX = Regex("(?i)(^batch\\b|\\bbatch\\s*(\\d+[-–]\\d+|\\(\\d+[-–]\\d+\\)))")

        fun schedule(context: Context, interval: SchedulePreferences.UploadDelayInterval) {
            val wm = WorkManager.getInstance(context)
            if (
                interval == SchedulePreferences.UploadDelayInterval.NEVER ||
                interval == SchedulePreferences.UploadDelayInterval.CUSTOM
            ) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val minutes = when (interval) {
                SchedulePreferences.UploadDelayInterval.THIRTY_MIN -> 30L
                SchedulePreferences.UploadDelayInterval.ONE_HOUR -> 60L
                SchedulePreferences.UploadDelayInterval.TWO_HOURS -> 120L
                SchedulePreferences.UploadDelayInterval.SIX_HOURS -> 360L
                SchedulePreferences.UploadDelayInterval.TWELVE_HOURS -> 720L
                SchedulePreferences.UploadDelayInterval.NEVER,
                SchedulePreferences.UploadDelayInterval.CUSTOM,
                -> return
            }
            val request = PeriodicWorkRequestBuilder<ScheduleRefreshWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}