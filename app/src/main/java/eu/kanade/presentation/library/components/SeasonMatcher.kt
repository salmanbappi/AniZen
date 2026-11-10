package eu.kanade.presentation.library.components

// ANZ --> Finds the other seasons of a series in the library by their titles ("Editar fondo" > copy to other seasons).
// Pure Kotlin on purpose: titles in, matches out.

internal object SeasonMatcher {

    private val brackets = Regex("[\\[(（【][^\\])）】]*[\\])）】]")
    private val symbols = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val markers = listOf(
        Regex("\\b(the )?(final|last) season\\b"),
        Regex("\\b(season|s|part|cour|temporada|parte)\\s*\\d+\\b"),
        Regex("\\b\\d+(st|nd|rd|th)\\s+(season|cour|part)\\b"),
        Regex("\\b(first|second|third|fourth|fifth|sixth|seventh)\\s+(season|cour|part)\\b"),
        Regex("\\b(season|temporada)\\b"),
    )
    private val tail = Regex("\\s(ii|iii|iv|v|vi|vii|viii|ix|x|\\d{1,2})$")

    /** The title without what tells one season from another ("Season 2", "2nd Season", "II", "Part 2", ...). */
    fun key(title: String): String {
        val plain = title.lowercase().replace(brackets, " ").replace(symbols, " ").replace(spaces, " ").trim()
        var t = plain
        for (r in markers) t = t.replace(r, " ")
        t = t.replace(spaces, " ").trim()
        repeat(2) { t = t.replace(tail, "").trim() }
        return t.ifEmpty { plain }
    }

    /** One title starting with the other one, at a word boundary ("sword art online" and "sword art online alicization"). */
    private fun related(a: String, b: String): Boolean {
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        return short.length >= 6 && long.length > short.length && long.startsWith(short) && long[short.length] == ' '
    }

    /** The two titles begin with the same long run of words (an old and a new title of the same series). */
    private fun sharedStart(a: String, b: String): Boolean {
        val wa = a.split(' ')
        val wb = b.split(' ')
        var n = 0
        while (n < wa.size && n < wb.size && wa[n] == wb[n]) n++
        return n >= 2 && wa.take(n).joinToString(" ").length >= 18
    }

    /** The other entries of [all] (id and title) that look like seasons of the same series as [title]. */
    fun relatives(id: Long, title: String, all: List<Pair<Long, String>>): List<Pair<Long, String>> {
        val mine = key(title)
        if (mine.length < 3) return emptyList()
        return all
            .filter { (otherId, otherTitle) ->
                if (otherId == id) {
                    false
                } else {
                    val other = key(otherTitle)
                    other == mine || related(mine, other) || sharedStart(mine, other)
                }
            }
            .sortedBy { it.second.lowercase() }
            .take(12)
    }
}
// ANZ <--
