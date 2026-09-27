package mihon.feature.announcements

import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.ZoneOffset

class AnnouncementsApi(
    private val trackerManager: TrackerManager = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchAnnouncements(maxPages: Int = MAX_PAGES): List<AnnouncementEntry> = withIOContext {
        val seenIds = mutableSetOf<Int>()
        val results = mutableListOf<AnnouncementEntry>()
        val pageLimit = maxPages.coerceIn(1, MAX_PAGES)

        for (page in 1..pageLimit) {
            val pageResult = fetchPage(page)
            val mediaList = pageResult.media
            if (mediaList.isEmpty()) break

            mediaList.forEach { dto ->
                if (seenIds.add(dto.id)) {
                    dto.toAnnouncementEntry()?.let { results.add(it) }
                }
            }

            if (results.size >= MAX_ENTRIES) break
            if (!pageResult.hasNextPage) break
        }

        results
    }

    private suspend fun fetchPage(page: Int): PageResult {
        val requestBody = GraphQlRequest(
            query = QUERY,
            variables = mapOf("page" to page, "perPage" to PER_PAGE),
        )
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody(jsonMediaType))
            .build()

        return trackerManager.aniList.api.newPublicCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("AniList announcements query failed: HTTP ${response.code}")
            }
            val body = response.body.string()
            val pageData = json.decodeFromString<GraphQlResponse>(body).data?.Page
            PageResult(
                media = pageData?.media.orEmpty(),
                hasNextPage = pageData?.pageInfo?.hasNextPage == true,
            )
        }
    }

    private fun MediaDto.toAnnouncementEntry(): AnnouncementEntry? {
        val titleText = title.english ?: title.userPreferred ?: title.romaji ?: return null
        val prequelEdge = relations?.edges?.firstOrNull { edge ->
            edge.relationType == "PREQUEL" && edge.node.type == "ANIME"
        }
        val otherRelationType = relations?.edges?.firstOrNull { edge ->
            edge.node.type == "ANIME" && edge.relationType in setOf("SPIN_OFF", "REMAKE")
        }?.relationType
        val relatedEdge = prequelEdge ?: relations?.edges?.firstOrNull { edge ->
            edge.node.type == "ANIME" &&
                edge.relationType in setOf("PARENT", "SPIN_OFF", "REMAKE", "SEQUEL")
        }
        val relatedTitle = relatedEdge?.node?.title?.english
            ?: relatedEdge?.node?.title?.userPreferred
            ?: relatedEdge?.node?.title?.romaji
        val category = AnnouncementTextBuilder.categoryFor(
            format = format,
            source = source,
            relationType = otherRelationType,
            hasPrequelEdge = prequelEdge != null,
        )

        val relatedYear = relatedEdge?.node?.startDate?.year
        val expectedYear = startDate?.year
        val exactReleaseDate = startDate
            ?.takeIf { it.year != null && it.month != null && it.day != null }
            ?.let { sd ->
                LocalDate.of(sd.year!!, sd.month!!, sd.day!!)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }

        return AnnouncementEntry(
            mediaId = id,
            title = titleText,
            category = category,
            description = AnnouncementTextBuilder.buildDescription(
                title = titleText,
                category = category,
                format = format,
                source = source,
                relatedTitle = relatedTitle,
                relatedYear = relatedYear,
                expectedYear = expectedYear,
            ),
            expectedYear = expectedYear,
            relatedAniListMediaId = relatedEdge?.node?.id,
            relatedTitle = relatedTitle,
            relatedYear = relatedYear,
            coverImageUrl = coverImage?.extraLarge ?: coverImage?.medium,
            bannerImageUrl = bannerImage,
            exactReleaseDate = exactReleaseDate,
            fetchedFrom = "AniList API",
            popularity = popularity,
            score = averageScore,
            isAdult = isAdult,
        )
    }

    companion object {
        private const val PER_PAGE = 25
        private const val MAX_PAGES = 50
        private const val MAX_ENTRIES = MAX_PAGES * PER_PAGE

        private const val QUERY = """
            query Announcements(${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo { hasNextPage }
                    media(status: NOT_YET_RELEASED, sort: [POPULARITY_DESC], type: ANIME) {
                        id
                        title { english userPreferred romaji }
                        format
                        source
                        averageScore
                        popularity
                        isAdult
                        startDate { year month day }
                        coverImage { extraLarge medium color }
                        bannerImage
                        relations {
                            edges {
                                relationType(version: 2)
                                node {
                                    id
                                    title { english userPreferred romaji }
                                    startDate { year }
                                    type
                                }
                            }
                        }
                    }
                }
            }
        """
    }
}

@Serializable
private data class GraphQlRequest(val query: String, val variables: Map<String, Int>)

@Serializable
private data class GraphQlResponse(val data: PageDataWrapper? = null)

@Serializable
private data class PageDataWrapper(val Page: PageData)

@Serializable
private data class PageData(
    val pageInfo: PageInfoDto? = null,
    val media: List<MediaDto> = emptyList(),
)

@Serializable
private data class PageInfoDto(val hasNextPage: Boolean = false)

@Serializable
private data class MediaDto(
    val id: Int,
    val title: TitleDto,
    val format: String? = null,
    val source: String? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val isAdult: Boolean = false,
    val startDate: StartDateDto? = null,
    val coverImage: CoverImageDto? = null,
    val bannerImage: String? = null,
    val relations: RelationsDto? = null,
)

@Serializable
private data class TitleDto(
    val english: String? = null,
    val userPreferred: String? = null,
    val romaji: String? = null,
)

private data class PageResult(
    val media: List<MediaDto>,
    val hasNextPage: Boolean,
)

@Serializable
private data class StartDateDto(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
)

@Serializable
private data class CoverImageDto(
    val extraLarge: String? = null,
    val medium: String? = null,
    val color: String? = null,
)

@Serializable
private data class RelationsDto(val edges: List<RelationEdgeDto> = emptyList())

@Serializable
private data class RelationEdgeDto(
    val relationType: String? = null,
    val node: RelationNodeDto,
)

@Serializable
private data class RelationNodeDto(
    val id: Int,
    val title: TitleDto,
    val startDate: StartDateDto? = null,
    val type: String? = null,
)