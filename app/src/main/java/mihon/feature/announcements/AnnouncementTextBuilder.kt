package mihon.feature.announcements

object AnnouncementTextBuilder {

    fun sourceLabel(source: String?): String? = when (source) {
        "MANGA" -> "manga"
        "LIGHT_NOVEL" -> "light novel"
        "VISUAL_NOVEL" -> "visual novel"
        "GAME" -> "game"
        "WEB_NOVEL" -> "web novel"
        "ORIGINAL" -> null
        else -> null
    }

    fun formatLabel(format: String?): String = when (format) {
        "TV" -> "TV anime"
        "MOVIE" -> "movie"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "SPECIAL" -> "special"
        else -> "anime"
    }

    fun categoryFor(
        format: String?,
        source: String?,
        relationType: String?,
        hasPrequelEdge: Boolean,
    ): AnnouncementCategory {
        return when {
            format == "MOVIE" -> AnnouncementCategory.MOVIE
            format in SPECIAL_FORMATS -> AnnouncementCategory.SPECIAL
            hasPrequelEdge -> AnnouncementCategory.NEW_SEASON
            relationType == "SPIN_OFF" -> AnnouncementCategory.SPIN_OFF
            relationType == "REMAKE" -> AnnouncementCategory.REMAKE
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
                "$title is a confirmed spin-off of the AniList franchise."
            AnnouncementCategory.REMAKE ->
                "$title is a confirmed remake of an existing anime."
            AnnouncementCategory.ORIGINAL ->
                "$title is an original anime project."
            AnnouncementCategory.SPECIAL ->
                "$title is a new ${formatLabel(format)} release."
        }
    }

    private val ADAPTABLE_SOURCES =
        setOf("MANGA", "LIGHT_NOVEL", "VISUAL_NOVEL", "GAME", "WEB_NOVEL")
    private val SPECIAL_FORMATS = setOf("SPECIAL", "OVA", "ONA")
}