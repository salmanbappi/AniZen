package eu.kanade.tachiyomi.util

import android.app.Application
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.simkl.Simkl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.ui.anime.track.TrackItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.feature.airingschedule.ScheduleDataRefreshWorker
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.OffsetDateTime
import java.util.Calendar

class AniChartApi {
    private val networkHelper: NetworkHelper by lazy { Injekt.get() }
    private val json: Json by lazy { Injekt.get() }
    private val client get() = networkHelper.client

    internal suspend fun loadAiringTime(
        anime: Anime,
        trackItems: List<TrackItem>,
        manualFetch: Boolean,
    ): Pair<Int, Long> {
        var airingEpisodeData = Pair(anime.nextEpisodeToAir, anime.nextEpisodeAiringAt)
        if (anime.status == SAnime.COMPLETED.toLong() && !manualFetch) return airingEpisodeData

        return withIOContext {
            // 1. Fast path: check the local airing schedule cache first
            val cachedResult = checkLocalScheduleCache(anime)
            if (cachedResult != null && cachedResult.second > 0L) {
                return@withIOContext cachedResult
            }

            // 2. If not cached, resolve via linked trackers (AniList / MAL / Simkl)
            val matchingTrackItem = trackItems.firstOrNull {
                (it.tracker is Anilist && it.track != null) ||
                    (it.tracker is MyAnimeList && it.track != null) ||
                    (it.tracker is Simkl && it.track != null)
            } ?: return@withIOContext airingEpisodeData

            matchingTrackItem.track?.let { track ->
                airingEpisodeData = when (matchingTrackItem.tracker) {
                    is Anilist -> getAnilistAiringEpisodeData(track.remoteId)
                    is MyAnimeList -> getAnilistAiringEpisodeData(getAlIdFromMal(track.remoteId))
                    is Simkl -> getSimklAiringEpisodeData(track.remoteId)
                    else -> Pair(1, 0L)
                }
            }
            return@withIOContext airingEpisodeData
        }
    }

    private suspend fun checkLocalScheduleCache(anime: Anime): Pair<Int, Long>? {
        return runCatching {
            val context = Injekt.get<Application>()
            val cache = ScheduleDataRefreshWorker.readCache(context) ?: return null
            val nowEpoch = System.currentTimeMillis() / 1000L

            val matchedEntry = cache.entries
                .filter { entry ->
                    entry.airingAt >= nowEpoch &&
                        mihon.feature.airingschedule.util.ScheduleTitleMatcher.matchesAny(
                            anime.title,
                            mihon.feature.airingschedule.util.ScheduleTitleMatcher.candidateTitlesFromEntry(entry),
                        )
                }
                .minByOrNull { it.airingAt }

            matchedEntry?.let { Pair(it.episode, it.airingAt) }
        }.getOrNull()
    }

    private suspend fun getAlIdFromMal(idMal: Long): Long {
        if (idMal <= 0L) return 0L
        return withIOContext {
            val query = "query(\$idMal:Int){Media(idMal:\$idMal,type:ANIME){id}}"
            val payload = buildJsonObject {
                put("query", query)
                put("variables", buildJsonObject { put("idMal", idMal) })
            }

            runCatching {
                val response = with(json) {
                    client.newCall(
                        POST(
                            API_URL,
                            body = payload.toString().toRequestBody(jsonMime),
                        ),
                    ).awaitSuccess().parseAs<ALMediaResponse>()
                }
                response.data?.media?.id ?: 0L
            }.getOrDefault(0L)
        }
    }

    private suspend fun getAnilistAiringEpisodeData(id: Long): Pair<Int, Long> {
        if (id <= 0L) return Pair(1, 0L)
        return withIOContext {
            val query = "query(\$id:Int){Media(id:\$id){nextAiringEpisode{episode airingAt}}}"
            val payload = buildJsonObject {
                put("query", query)
                put("variables", buildJsonObject { put("id", id) })
            }

            runCatching {
                val response = with(json) {
                    client.newCall(
                        POST(
                            API_URL,
                            body = payload.toString().toRequestBody(jsonMime),
                        ),
                    ).awaitSuccess().parseAs<ALMediaResponse>()
                }
                val next = response.data?.media?.nextAiringEpisode
                if (next != null) {
                    Pair(next.episode, next.airingAt)
                } else {
                    Pair(1, 0L)
                }
            }.getOrDefault(Pair(1, 0L))
        }
    }

    private suspend fun getSimklAiringEpisodeData(id: Long): Pair<Int, Long> {
        var episodeNumber = 1
        var airingAt = 0L
        return withIOContext {
            val calendarTypes = listOf("anime", "tv", "movie_release")
            calendarTypes.forEach {
                val response = runCatching {
                    client.newCall(GET("https://data.simkl.in/calendar/$it.json")).awaitSuccess()
                }.getOrNull() ?: return@forEach

                val body = runCatching { response.body.string() }.getOrDefault("")
                val data = removeAiredSimkl(body)

                val malId = data.substringAfter("\"simkl_id\":$id,", "").substringAfter(
                    "\"mal\":\"",
                ).substringBefore("\"").toLongOrNull() ?: 0L
                if (malId != 0L) {
                    return@withIOContext getAnilistAiringEpisodeData(
                        getAlIdFromMal(malId),
                    )
                }

                val epNum = data.substringAfter("\"simkl_id\":$id,", "").substringBefore("\"}}").substringAfterLast(
                    "\"episode\":",
                )
                episodeNumber = epNum.substringBefore(",").toIntOrNull() ?: episodeNumber

                val date = data.substringBefore("\"simkl_id\":$id,", "").substringAfterLast(
                    "\"date\":\"",
                ).substringBefore("\"")
                airingAt = if (date.isNotBlank()) toUnixTimestamp(date) else airingAt

                if (airingAt != 0L) return@withIOContext Pair(episodeNumber, airingAt)
            }
            return@withIOContext Pair(episodeNumber, airingAt)
        }
    }

    private fun removeAiredSimkl(body: String): String {
        val currentTimeInMillis = Calendar.getInstance().timeInMillis
        val index = body.split("\"date\":\"").drop(1).indexOfFirst {
            val date = it.substringBefore("\"")
            val time = if (date.isNotBlank()) toUnixTimestamp(date) else 0L
            time.times(1000) > currentTimeInMillis
        }
        return if (index >= 0) body.substring(index) else ""
    }

    private fun toUnixTimestamp(dateFormat: String): Long {
        return runCatching {
            val offsetDateTime = OffsetDateTime.parse(dateFormat)
            offsetDateTime.toInstant().epochSecond
        }.getOrDefault(0L)
    }

    companion object {
        private const val API_URL = "https://graphql.anilist.co"
    }
}

@Serializable
private data class ALMediaResponse(
    val data: ALMediaData? = null,
    val errors: List<ALMediaError>? = null,
)

@Serializable
private data class ALMediaError(val message: String? = null)

@Serializable
private data class ALMediaData(@SerialName("Media") val media: ALMediaItem? = null)

@Serializable
private data class ALMediaItem(
    val id: Long? = null,
    val nextAiringEpisode: ALNextEpisode? = null,
)

@Serializable
private data class ALNextEpisode(
    val episode: Int,
    val airingAt: Long,
)
