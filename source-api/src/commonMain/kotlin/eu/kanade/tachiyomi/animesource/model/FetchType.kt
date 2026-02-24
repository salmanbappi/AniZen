package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.Serializable

/**
 * Define what type of content the anime should fetch.
 * 
 * @since extensions-lib 16
 */
@Serializable
enum class FetchType {
    Seasons,
    Episodes,
}
