package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ANZ -->
@Serializable
data class ALOAuth(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    val expires: Long = System.currentTimeMillis() + 365L * 86400_000L,
    @SerialName("expires_in")
    val expiresIn: Long = 365L * 86400_000L,
) {
    fun isExpired() = System.currentTimeMillis() + 60_000L > expires
}
// ANZ <--
