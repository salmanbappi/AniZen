package mihon.feature.announcements

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Uses Jikan's structured upcoming-season endpoint when AniList is unavailable.
 * The AniList website is client-rendered and cannot be used as a plain HTTP fallback.
 */
class AnnouncementsFallbackApi(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    fun fetchAnnouncements(): List<AnnouncementEntry> {
        return try {
            fetchInternal()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "AnnouncementsFallbackApi: Jikan fetch failed, returning empty list"
            }
            emptyList()
        }
    }

    private fun fetchInternal(): List<AnnouncementEntry> {
        val request = Request.Builder()
            .url("https://api.jikan.moe/v4/seasons/upcoming")
            .build()

        val body = networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body.string()
        }

        val parsed = json.decodeFromString<JikanUpcomingResponse>(body)
        return parsed.data.mapNotNull { it.toAnnouncementEntry() }
    }

    private fun JikanAnimeDto.toAnnouncementEntry(): AnnouncementEntry? {
        val titleText = titleEnglish ?: title ?: return null
        return AnnouncementEntry(
            // Keep fallback ids in a separate namespace so a MAL id cannot
            // collide with an AniList id in the cache or watchlist.
            mediaId = -malId,
            title = titleText,
            category = AnnouncementCategory.ADAPTATION,
            description = AnnouncementTextBuilder.buildDescription(
                title = titleText,
                category = AnnouncementCategory.ADAPTATION,
                format = type,
                source = null,
                relatedTitle = null,
                relatedYear = null,
                expectedYear = null,
            ),
            expectedYear = null,
            relatedAniListMediaId = null,
            relatedTitle = null,
            relatedYear = null,
            coverImageUrl = images?.jpg?.largeImageUrl ?: images?.jpg?.imageUrl,
            bannerImageUrl = null,
            exactReleaseDate = null,
            fetchedFrom = "MAL (Jikan, fallback)",
        )
    }
}

@Serializable
private data class JikanUpcomingResponse(val data: List<JikanAnimeDto> = emptyList())

@Serializable
private data class JikanAnimeDto(
    @SerialName("mal_id") val malId: Int,
    val title: String? = null,
    @SerialName("title_english") val titleEnglish: String? = null,
    val type: String? = null,
    val images: JikanImagesDto? = null,
)

@Serializable
private data class JikanImagesDto(val jpg: JikanJpgDto? = null)

@Serializable
private data class JikanJpgDto(
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("large_image_url") val largeImageUrl: String? = null,
)