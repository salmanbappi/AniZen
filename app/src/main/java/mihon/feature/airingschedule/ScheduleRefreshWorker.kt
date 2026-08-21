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
                    "${recentObservations.size} episode observation(s) in 2-day journal" +
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
        val weekStart = now.minusDays(2)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toLocalDate().atStartOfDay(zone)
        val weekEnd = weekStart.plusDays(7).minusSeconds(1)
        val entries = AiringScheduleRepository().getWeeklySchedule(
            weekStart.toEpochSecond(),
            weekEnd.toEpochSecond(),
            includeAdult = schedulePrefs.showAdultContent().get(),
        )
        val windowStart = now.minusDays(2).toEpochSecond()
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
                    val entry = airedEntries.firstOrNull {
                        it.episode.toFloat() == episode.episode_number &&
                            titlesMatch(it, anime.title)
                    } ?: continue

                    val sourceUploadAt = episode.date_upload / 1000L
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
        val source = normalizeTitle(sourceTitle)
        if (source.length < 3) return false
        return listOfNotNull(
            entry.titleUserPreferred,
            entry.titleEnglish,
            entry.titleRomaji,
            entry.titleNative,
        ).map(::normalizeTitle).any { candidate ->
            if (candidate.length < 3) return@any false
            candidate == source ||
                (candidate.length >= 6 && source.length >= 6 && (candidate.contains(source) || source.contains(candidate)))
        }
    }

    private fun normalizeTitle(value: String) =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

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
        private const val MIN_DELAY_MINUTES = -60L
        private const val MAX_DELAY_MINUTES = 24L * 60L

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