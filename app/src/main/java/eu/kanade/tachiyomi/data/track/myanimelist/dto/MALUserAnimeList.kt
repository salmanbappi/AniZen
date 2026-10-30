package eu.kanade.tachiyomi.data.track.myanimelist.dto

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.myanimelist.getStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MALUserAnimeList(
    val data: List<MALUserAnimeListItem>,
    val paging: MALUserAnimeListPaging,
)

@Serializable
data class MALUserAnimeListItem(
    val node: MALUserAnimeListNode,
    @SerialName("list_status")
    val listStatus: MALListItemStatus,
) {
    fun toMALUserAnime(): MALUserAnime {
        return MALUserAnime(
            nodeId = node.id,
            title = node.title,
            coverUrl = node.covers?.large ?: node.covers?.medium ?: "",
            numEpisodes = node.numEpisodes,
            listStatus = listStatus.status,
            score = listStatus.score,
            episodesSeen = listStatus.numEpisodesWatched.toInt(),
            isRewatching = listStatus.isRewatching,
            startDate = listStatus.startDate ?: "",
            finishDate = listStatus.finishDate ?: "",
        )
    }
}

@Serializable
data class MALUserAnimeListNode(
    val id: Long,
    val title: String,
    @SerialName("main_picture")
    val covers: MALAnimeCovers? = null,
    @SerialName("num_episodes")
    val numEpisodes: Long = 0,
    val synopsis: String = "",
)

@Serializable
data class MALUserAnimeListPaging(
    val next: String? = null,
)

data class MALUserAnime(
    val nodeId: Long,
    val title: String,
    val coverUrl: String,
    val numEpisodes: Long,
    val listStatus: String,
    val score: Int,
    val episodesSeen: Int,
    val isRewatching: Boolean,
    val startDate: String,
    val finishDate: String,
) {
    fun toTrack() = Track.create(1L).apply {
        remote_id = nodeId
        title = this@MALUserAnime.title
        status = if (isRewatching) MyAnimeList.REWATCHING else getStatus(listStatus)
        score = this@MALUserAnime.score.toDouble()
        last_episode_seen = episodesSeen.toDouble()
        total_episodes = numEpisodes
    }
}
