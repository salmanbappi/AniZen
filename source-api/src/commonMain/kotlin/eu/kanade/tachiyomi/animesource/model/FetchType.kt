package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.Serializable

@Serializable
enum class FetchType {
    SHORT,
    ALL,
    EPISODES,
    Episodes, // Some extensions might use this casing
}
