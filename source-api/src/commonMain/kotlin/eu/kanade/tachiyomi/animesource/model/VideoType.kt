package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.Serializable

@Serializable
enum class VideoType {
    VIDEO,
    HLS,
    DASH,
}
