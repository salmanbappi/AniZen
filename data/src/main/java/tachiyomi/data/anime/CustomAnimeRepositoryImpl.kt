package tachiyomi.data.anime

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.anime.model.CustomAnimeInfo
import tachiyomi.domain.anime.repository.CustomAnimeRepository
import java.io.File

class CustomAnimeRepositoryImpl(context: Context) : CustomAnimeRepository {
    private val editJson = File(context.getExternalFilesDir(null), "edits.json")

    private val customAnimeMap = fetchCustomData()
    private val mutex = Mutex()
    private val _changeEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val changeEvents = _changeEvents.asSharedFlow()

    override fun get(animeId: Long) = customAnimeMap[animeId]

    override fun subscribe(animeId: Long): Flow<CustomAnimeInfo?> {
        return changeEvents
            .filter { it == animeId }
            .onStart { emit(animeId) }
            .map { get(it) }
    }

    private fun fetchCustomData(): MutableMap<Long, CustomAnimeInfo> {
        if (!editJson.exists() || !editJson.isFile) return mutableMapOf()

        val json = try {
            Json.decodeFromString<AnimeList>(
                editJson.bufferedReader().use { it.readText() },
            )
        } catch (e: Exception) {
            null
        } ?: return mutableMapOf()

        val animesJson = json.animes ?: return mutableMapOf()
        return animesJson
            .mapNotNull { animeJson ->
                val id = animeJson.id ?: return@mapNotNull null
                id to animeJson.toAnime()
            }
            .toMap()
            .toMutableMap()
    }

    override fun set(animeInfo: CustomAnimeInfo) {
        tachiyomi.core.common.util.lang.launchIO {
            mutex.withLock {
                if (
                    animeInfo.title == null &&
                    animeInfo.author == null &&
                    animeInfo.artist == null &&
                    animeInfo.thumbnailUrl == null &&
                    animeInfo.description == null &&
                    animeInfo.genre == null &&
                    animeInfo.status == null &&
                    animeInfo.score == null &&
                    animeInfo.note == null
                ) {
                    customAnimeMap.remove(animeInfo.id)
                } else {
                    customAnimeMap[animeInfo.id] = animeInfo
                }
                saveCustomInfo()
                _changeEvents.emit(animeInfo.id)
            }
        }
    }

    private fun saveCustomInfo() {
        val jsonElements = customAnimeMap.values.map { it.toJson() }
        // ANZ -->
        editJson.delete()
        if (jsonElements.isNotEmpty()) {
            editJson.writeText(Json.encodeToString(AnimeList(jsonElements)))
        }
        // ANZ <--
    }

    @Serializable
    data class AnimeList(
        val animes: List<AnimeJson>? = null,
    )

    @Serializable
    data class AnimeJson(
        var id: Long? = null,
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val thumbnailUrl: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: Long? = null,
        val score: Double? = null,
        val note: String? = null,
    ) {

        fun toAnime() = CustomAnimeInfo(
            id = this@AnimeJson.id!!,
            title = this@AnimeJson.title?.takeUnless { it.isBlank() },
            author = this@AnimeJson.author,
            artist = this@AnimeJson.artist,
            thumbnailUrl = this@AnimeJson.thumbnailUrl,
            description = this@AnimeJson.description,
            genre = this@AnimeJson.genre,
            status = this@AnimeJson.status?.takeUnless { it == 0L },
            score = this@AnimeJson.score,
            note = this@AnimeJson.note,
        )
    }

    private fun CustomAnimeInfo.toJson(): AnimeJson {
        return AnimeJson(
            id,
            title,
            author,
            artist,
            thumbnailUrl,
            description,
            genre,
            status,
            score,
            note,
        )
    }
}
