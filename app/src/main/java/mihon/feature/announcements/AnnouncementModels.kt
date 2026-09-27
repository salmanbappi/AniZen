package mihon.feature.announcements

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
enum class AnnouncementCategory {
    NEW_SEASON,
    ADAPTATION,
    SPIN_OFF,
    REMAKE,
    ORIGINAL,
    MOVIE,
    SPECIAL,
}

@Serializable
enum class AnnouncementSort {
    AIRING_SOON,
    LATEST_ADDED,
    POPULARITY,
    SCORE,
    TITLE,
}

@Serializable
enum class AnnouncementAutoRefresh {
    OFF,
    SIX_HOURS,
    TWELVE_HOURS,
    ONE_DAY,
    TWO_DAYS,
    FIVE_DAYS,
    SEVEN_DAYS,
}

fun AnnouncementAutoRefresh.durationMillis(): Long? = when (this) {
    AnnouncementAutoRefresh.OFF -> null
    AnnouncementAutoRefresh.SIX_HOURS -> 6 * 60 * 60 * 1000L
    AnnouncementAutoRefresh.TWELVE_HOURS -> 12 * 60 * 60 * 1000L
    AnnouncementAutoRefresh.ONE_DAY -> 24 * 60 * 60 * 1000L
    AnnouncementAutoRefresh.TWO_DAYS -> 2 * 24 * 60 * 60 * 1000L
    AnnouncementAutoRefresh.FIVE_DAYS -> 5 * 24 * 60 * 60 * 1000L
    AnnouncementAutoRefresh.SEVEN_DAYS -> 7 * 24 * 60 * 60 * 1000L
}

@Serializable
@Parcelize
data class AnnouncementEntry(
    val mediaId: Int,
    val title: String,
    val category: AnnouncementCategory,
    val description: String,
    val expectedYear: Int?,
    val relatedAniListMediaId: Int?,
    val relatedTitle: String?,
    val relatedYear: Int?,
    val coverImageUrl: String?,
    val bannerImageUrl: String?,
    val exactReleaseDate: Long?,
    val fetchedFrom: String,
    val popularity: Int? = null,
    val score: Int? = null,
    val isAdult: Boolean = false,
) : Parcelable