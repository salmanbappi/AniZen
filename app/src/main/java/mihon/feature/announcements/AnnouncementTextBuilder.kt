package mihon.feature.announcements

object AnnouncementTextBuilder {

    fun sourceLabel(source: String?): String? = when (source) {
        "MANGA" -> "manga"
        "LIGHT_NOVEL" -> "light novel"
        "VISUAL_NOVEL" -> "visual novel"
        "GAME", "VIDEO_GAME" -> "game"
        "WEB_NOVEL" -> "web novel"
        "NOVEL" -> "novel"
        "WEB_MANGA" -> "web manga"
        "COMIC" -> "comic"
        "DOUJINSHI" -> "doujinshi"
        "PICTURE_BOOK" -> "picture book"
        "MULTIMEDIA_PROJECT" -> "multimedia project"
        "LIVE_ACTION" -> "live action"
        "ORIGINAL" -> null
        else -> null
    }

    fun formatLabel(format: String?): String = when (format) {
        "TV", "TV_SHORT" -> "TV anime"
        "MOVIE" -> "movie"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "SPECIAL" -> "special"
        else -> "anime"
    }

    private val SEASON_REGEX = Regex(
        """(?i)\b(?:season\s*\d+|s\d+|\d+(?:nd|rd|th|st)\s*season|final\s*season|part\s*\d+|cour\s*\d+|2nd|3rd|4th|5th|ii|iii|iv|v|vi)\b""",
    )
    private val REMAKE_REGEX = Regex(
        """(?i)\b(?:remake|reboot|re-animation|re:version)\b""",
    )
    private val SPIN_OFF_REGEX = Regex(
        """(?i)\b(?:spin[- ]?off|gaiden|side\s*story)\b""",
    )

    fun categoryFor(
        format: String?,
        source: String?,
        hasPrequelOrParent: Boolean = false,
        hasSpinOffOrSideStory: Boolean = false,
        hasAlternative: Boolean = false,
        title: String? = null,
    ): AnnouncementCategory {
        val titleMatchesSeason = title != null && SEASON_REGEX.containsMatchIn(title)
        val titleMatchesRemake = title != null && REMAKE_REGEX.containsMatchIn(title)
        val titleMatchesSpinOff = title != null && SPIN_OFF_REGEX.containsMatchIn(title)

        return when {
            format == "MOVIE" -> AnnouncementCategory.MOVIE
            hasPrequelOrParent || titleMatchesSeason -> AnnouncementCategory.NEW_SEASON
            hasAlternative || titleMatchesRemake -> AnnouncementCategory.REMAKE
            hasSpinOffOrSideStory || titleMatchesSpinOff -> AnnouncementCategory.SPIN_OFF
            format in SPECIAL_FORMATS -> AnnouncementCategory.SPECIAL
            source in ADAPTABLE_SOURCES -> AnnouncementCategory.ADAPTATION
            else -> AnnouncementCategory.ORIGINAL
        }
    }

    fun buildDescription(
        title: String,
        category: AnnouncementCategory,
        format: String?,
        source: String?,
        relatedTitle: String?,
        relatedYear: Int?,
        expectedYear: Int?,
    ): String {
        val fmt = formatLabel(format)
        return when (category) {
            AnnouncementCategory.ADAPTATION -> {
                val src = sourceLabel(source)
                if (src != null) {
                    "$title is getting a $fmt adaptation, based on the original $src."
                } else {
                    "$title is getting a $fmt adaptation."
                }
            }
            AnnouncementCategory.NEW_SEASON -> {
                when {
                    relatedTitle != null && relatedYear != null ->
                        "A new season of $relatedTitle has been confirmed, continuing the story from $relatedYear."
                    relatedTitle != null ->
                        "A new season of $relatedTitle has been confirmed."
                    else ->
                        "$title has been confirmed for a new season."
                }
            }
            AnnouncementCategory.MOVIE -> {
                val yearClause = expectedYear?.let { ", expected in $it." } ?: "."
                "$title is getting a new $fmt$yearClause"
            }
            AnnouncementCategory.SPIN_OFF ->
                "$title is a confirmed spin-off project."
            AnnouncementCategory.REMAKE ->
                "$title is a confirmed remake / reboot of an existing anime."
            AnnouncementCategory.ORIGINAL ->
                "$title is an original anime project."
            AnnouncementCategory.SPECIAL ->
                "$title is a new ${formatLabel(format)} release."
        }
    }

    private val ADAPTABLE_SOURCES = setOf(
        "MANGA", "LIGHT_NOVEL", "VISUAL_NOVEL", "GAME", "VIDEO_GAME",
        "WEB_NOVEL", "NOVEL", "WEB_MANGA", "COMIC", "DOUJINSHI",
        "PICTURE_BOOK", "MULTIMEDIA_PROJECT", "LIVE_ACTION",
    )
    private val SPECIAL_FORMATS = setOf("SPECIAL", "OVA", "ONA")
}