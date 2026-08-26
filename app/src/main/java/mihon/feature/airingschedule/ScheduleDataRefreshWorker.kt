package mihon.feature.airingschedule

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Data
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that automatically refreshes the weekly airing schedule
 * from AniList and caches it locally. Frequency is configurable by the user
 * (1–7 days). Disabled by default.
 */
class ScheduleDataRefreshWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val schedulePrefs = Injekt.get<SchedulePreferences>()

            val forceRefresh = inputData.getBoolean(INPUT_FORCE_REFRESH, false)
            if (!forceRefresh && !schedulePrefs.scheduleAutoRefreshEnabled().get()) {
                return@withContext Result.success()
            }

            val repository = AiringScheduleRepository()
            val includeAdult = schedulePrefs.showAdultContent().get()

            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(zone)
            val weekEnd = weekStart.plusDays(7).minusSeconds(1)

            // Optimize schedule window:
            // - Past: 1 week past release date (7 days ago, or start of current week, whichever is earlier)
            // - Future: Focus on upcoming release dates (rolling 30 days ahead from today)
            val pastWeekStart = now.minusDays(7).toLocalDate().atStartOfDay(zone)
            val fetchStart = if (weekStart.isBefore(pastWeekStart)) weekStart else pastWeekStart
            val fetchEnd = now.plusDays(30).toLocalDate().atTime(23, 59, 59).atZone(zone).let {
                if (weekEnd.isAfter(it)) weekEnd else it
            }

            val entries = repository.getSchedule(
                fetchStart.toEpochSecond(),
                fetchEnd.toEpochSecond(),
                includeAdult = includeAdult,
            )

            writeCache(context, fetchStart.toEpochSecond(), entries)

            // Re-arm alarms for any anime the user subscribed to "notify every episode" for.
            // Series alarms are only ever scheduled from whichever week is currently loaded in
            // the UI, so without this, a series subscription silently stops alerting once the
            // originally-loaded week's episodes are exhausted. This periodic refresh (which runs
            // independently of the Schedule tab being open) re-schedules alarms for each newly
            // fetched week's unaired episodes belonging to a subscribed series.
            val seriesIds = schedulePrefs.notifySeriesMediaIds().get()
            if (seriesIds.isNotEmpty()) {
                entries
                    .filter { it.mediaId.toString() in seriesIds && !it.hasAired() }
                    .forEach { mihon.feature.airingschedule.notification.ScheduleNotifications.ensureScheduled(context, it) }
            }

            schedulePrefs.scheduleLastAutoRefresh().set(System.currentTimeMillis())
            Result.success()
        } catch (_: Exception) {
            // WorkManager will retry this with the exponential backoff policy configured in
            // `schedule()` below, so a transient failure here (rate limiting, no network, etc.)
            // self-heals without any user action.
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "ScheduleDataRefreshWorker"
        private val cacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun Context.cacheFile() = java.io.File(filesDir, "schedule_cache.json")

        fun schedule(context: Context, frequency: SchedulePreferences.AutoRefreshFrequency) {
            val days = frequency.toDays()
            val request = PeriodicWorkRequestBuilder<ScheduleDataRefreshWorker>(days, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                // If a refresh attempt fails (e.g. transient network/rate-limit issue), WorkManager
                // retries with growing delays instead of hammering the API or giving up until the
                // next full period, so the "one week ends, next week fails to load" scenario
                // self-heals well before the week is over.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScheduleDataRefreshWorker>()
                .setInputData(Data.Builder().putBoolean(INPUT_FORCE_REFRESH, true).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ScheduleDataRefreshWorker.immediate",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        private const val INPUT_FORCE_REFRESH = "force_refresh"

        private var inMemoryCache: ScheduleCacheData? = null

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        suspend fun readCache(context: Context): ScheduleCacheData? = withContext(Dispatchers.IO) {
            inMemoryCache?.let { return@withContext it }
            try {
                val file = context.cacheFile()
                if (!file.exists()) return@withContext null
                val data = cacheJson.decodeFromString(ScheduleCacheData.serializer(), file.readText())
                inMemoryCache = data
                data
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Persists a successfully fetched schedule to disk so it survives app process death and
         * can be used as a fallback if a later refresh attempt fails. Safe to call regardless of
         * whether auto-refresh is enabled. Also syncs upcoming exact air times to matched library anime.
         */
        suspend fun writeCache(context: Context, monthStartEpoch: Long, entries: List<AiringScheduleEntry>) =
            withContext(Dispatchers.IO) {
                if (entries.isEmpty()) return@withContext
                try {
                    val cacheData = ScheduleCacheData(
                        fetchedAt = System.currentTimeMillis(),
                        weekStartEpoch = monthStartEpoch,
                        monthStartEpoch = monthStartEpoch,
                        entries = entries,
                    )
                    inMemoryCache = cacheData
                    val file = context.cacheFile()
                    file.parentFile?.mkdirs()
                    file.writeText(cacheJson.encodeToString(ScheduleCacheData.serializer(), cacheData))
                    syncLibraryNextUpdate(entries)
                } catch (_: Exception) {
                    // Best-effort cache write; failing to persist shouldn't break the current load.
                }
            }

        suspend fun syncLibraryNextUpdate(entries: List<AiringScheduleEntry>) = withContext(Dispatchers.IO) {
            if (entries.isEmpty()) return@withContext
            runCatching {
                val getLibraryAnime = Injekt.get<tachiyomi.domain.anime.interactor.GetLibraryAnime>()
                val animeRepository = Injekt.get<tachiyomi.domain.anime.repository.AnimeRepository>()
                val libraryAnime = getLibraryAnime.await()
                if (libraryAnime.isEmpty()) return@withContext

                val nowEpoch = System.currentTimeMillis() / 1000L
                val upcomingEntries = entries.filter { it.airingAt >= nowEpoch }

                val updates = mutableListOf<tachiyomi.domain.anime.model.AnimeUpdate>()
                for (item in libraryAnime) {
                    val animeTitle = item.anime.title
                    if (item.anime.status == eu.kanade.tachiyomi.animesource.model.SAnime.COMPLETED.toLong()) {
                        if (item.anime.nextUpdate > 0L) {
                            updates.add(
                                tachiyomi.domain.anime.model.AnimeUpdate(
                                    id = item.anime.id,
                                    nextUpdate = 0L,
                                ),
                            )
                        }
                        continue
                    }

                    val matchedEntry = upcomingEntries
                        .filter { entry ->
                            mihon.feature.airingschedule.util.ScheduleTitleMatcher.matchesAny(
                                animeTitle,
                                mihon.feature.airingschedule.util.ScheduleTitleMatcher.candidateTitlesFromEntry(entry),
                            )
                        }
                        .minByOrNull { it.airingAt }

                    if (matchedEntry != null) {
                        val nextUpdateMillis = matchedEntry.airingAt * 1000L
                        if (item.anime.nextUpdate != nextUpdateMillis) {
                            updates.add(
                                tachiyomi.domain.anime.model.AnimeUpdate(
                                    id = item.anime.id,
                                    nextUpdate = nextUpdateMillis,
                                ),
                            )
                        }
                    } else if (item.anime.nextUpdate in 1 until ((nowEpoch - 86400L * 7) * 1000L)) {
                        updates.add(
                            tachiyomi.domain.anime.model.AnimeUpdate(
                                id = item.anime.id,
                                nextUpdate = 0L,
                            ),
                        )
                    }
                }

                if (updates.isNotEmpty()) {
                    animeRepository.updateAll(updates)
                }
            }
        }

        fun isCacheFresh(cacheData: ScheduleCacheData, frequency: SchedulePreferences.AutoRefreshFrequency): Boolean {
            val maxAge = frequency.toDays() * 24L * 60L * 60L * 1000L
            val age = System.currentTimeMillis() - cacheData.fetchedAt
            return age < maxAge
        }

        private fun SchedulePreferences.AutoRefreshFrequency.toDays(): Long = when (this) {
            SchedulePreferences.AutoRefreshFrequency.EVERY_1_DAY -> 1L
            SchedulePreferences.AutoRefreshFrequency.EVERY_2_DAYS -> 2L
            SchedulePreferences.AutoRefreshFrequency.EVERY_3_DAYS -> 3L
            SchedulePreferences.AutoRefreshFrequency.EVERY_4_DAYS -> 4L
            SchedulePreferences.AutoRefreshFrequency.EVERY_5_DAYS -> 5L
            SchedulePreferences.AutoRefreshFrequency.EVERY_6_DAYS -> 6L
            SchedulePreferences.AutoRefreshFrequency.EVERY_7_DAYS -> 7L
        }
    }
}

@Serializable
data class ScheduleCacheData(
    val fetchedAt: Long,
    val weekStartEpoch: Long = 0L,
    val monthStartEpoch: Long = 0L,
    val entries: List<AiringScheduleEntry>,
)
