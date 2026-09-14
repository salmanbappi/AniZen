package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.Serializable

@Serializable
data class ALRelationResult(
    val data: ALRelationData,
)

@Serializable
data class ALRelationData(
    val Media: ALRelationMedia? = null,
)

@Serializable
data class ALRelationMedia(
    val relations: ALRelationConnection? = null,
)

@Serializable
data class ALRelationConnection(
    val edges: List<ALRelationEdge>? = null,
)

@Serializable
data class ALRelationEdge(
    val relationType: String,
    val node: ALRelationNode,
)

@Serializable
data class ALRelationNode(
    val id: Int,
    val title: ALRelationTitle,
    val coverImage: ALRelationCoverImage? = null,
)

@Serializable
data class ALRelationTitle(
    val userPreferred: String,
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class ALRelationCoverImage(
    val large: String? = null,
)
