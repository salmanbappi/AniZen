package eu.kanade.tachiyomi.data.track.simkl.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
// ANZ -->
@OptIn(ExperimentalSerializationApi::class)
data class SimklSyncWatched(
    val result: Boolean?,
    @SerialName("last_watched_at")
    @JsonNames("last_watched")
    val lastWatchedAt: String?,
    val list: String?,
)
// ANZ <--
