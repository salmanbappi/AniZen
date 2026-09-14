package tachiyomi.core.metadata.tachiyomi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class EpisodeDetails(
    val episode_number: Float,
    val name: String? = null,
    val date_upload: String? = null,
    val scanlator: String? = null,
    val summary: String? = null,
    @SerialName("preview_url")
    val preview_url: String? = null,
)
