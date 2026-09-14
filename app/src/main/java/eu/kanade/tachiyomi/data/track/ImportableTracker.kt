package eu.kanade.tachiyomi.data.track

import dev.icerock.moko.resources.StringResource

interface ImportableTracker {
    val id: Long
    val name: String
    val isLoggedIn: Boolean
    fun getUsername(): String

    suspend fun getImportableList(): List<ImportableEntry>
    fun getNoticeStringRes(): StringResource
}

data class ImportableEntry(
    val remoteId: Long,
    val title: String,
    val coverUrl: String,
    val totalEpisodes: Long,
    val episodesSeen: Int,
    val score: Double,
    val status: Long, // Local track status
    val statusFilter: ImportStatusFilter?,
    val startDate: Long,
    val finishDate: Long,
    val trackingUrl: String,
)
