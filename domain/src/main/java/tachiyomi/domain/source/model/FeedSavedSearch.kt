package tachiyomi.domain.source.model

data class FeedSavedSearch(
    // Tag identifier, unique
    val id: Long,

    // Source for the saved search
    val source: Long,

    // If null then get latest/popular, if set get the saved search
    val savedSearch: Long?,

    // If the feed is a global (FeedScreen) or source specific feed (SourceFeedScreen)
    val global: Boolean,
    val feedOrder: Long,
    val type: Int,
    val category: Long = 1,
    val lastTopUrl: String? = null,
) {
    enum class Type(val value: Int) {
        Popular(0),
        Latest(1),
        SavedSearch(2),
        ;

        companion object {
            fun from(value: Int): Type = entries.find { it.value == value } ?: Latest
        }
    }
}
