package app.jubal.qeshev

import java.util.Locale

/**
 * Forgiving matching for names typed in Hebrew: "גימבו גיי" should find "ג'ימבו ג'יי".
 */
object Spelling {
    // Geresh, gershayim, apostrophes and quotes, which people often leave out
    private val marks = Regex("['׳’‘`´\"״“”]")
    private val wordStart = Regex("(^|[\\s\\-])([גזצ])(?=[א-ת])")

    /** A comparison key: no marks, no spaces or punctuation, final letters as regular ones. */
    fun key(text: String): String {
        val plain = marks.replace(text.lowercase(Locale.ROOT), "")
        val out = StringBuilder(plain.length)
        for (c in plain) {
            if (!c.isLetterOrDigit()) continue
            out.append(
                when (c) {
                    'ך' -> 'כ'
                    'ם' -> 'מ'
                    'ן' -> 'נ'
                    'ף' -> 'פ'
                    'ץ' -> 'צ'
                    else -> c
                }
            )
        }
        return out.toString()
    }

    /**
     * The same query with a geresh after ג, ז and צ at the start of words (the J, Zh and Ch
     * sounds of names like ג'ימבו, ז'ק, צ'רלי). Null when there is nothing to add.
     */
    fun withGeresh(query: String): String? {
        if (query.contains('\'') || query.contains('׳')) return null
        val out = wordStart.replace(query) { m -> m.groupValues[1] + m.groupValues[2] + "'" }
        return out.takeIf { it != query }
    }

    /** True when [name] is what the query asks for (the same, or one starts with the other). */
    fun matches(name: String, queryKey: String): Boolean {
        if (queryKey.length < 2) return false
        val k = key(name)
        if (k.isEmpty()) return false
        return k == queryKey || k.startsWith(queryKey) || (k.length >= 3 && queryKey.startsWith(k))
    }

    /** How well a search result fits: 2 for the asked-for artist or name, 1 if the title contains it. */
    fun fit(result: YtResult, queryKey: String): Int = when (result) {
        is YtArtistResult -> if (matches(result.artist.name, queryKey)) 2 else 0
        is YtListResult -> when {
            matches(result.list.title, queryKey) || matches(result.list.artist, queryKey) -> 2
            key(result.list.title).contains(queryKey) -> 1
            else -> 0
        }
        is YtTrackResult -> when {
            matches(result.track.artist, queryKey) || matches(result.track.title, queryKey) -> 2
            key(result.track.title + result.track.artist).contains(queryKey) -> 1
            else -> 0
        }
    }

    fun idOf(result: YtResult): String = when (result) {
        is YtArtistResult -> "a:" + result.artist.key
        is YtListResult -> "l:" + result.list.url
        is YtTrackResult -> "t:" + result.track.id
    }

    /**
     * Joins results of the typed query and its variant: the best-fitting first,
     * otherwise in the order YouTube gave them, without repeats.
     */
    fun merge(queryKey: String, vararg lists: List<YtResult>): List<YtResult> {
        val seen = HashSet<String>()
        val all = ArrayList<YtResult>()
        for (list in lists) for (r in list) if (seen.add(idOf(r))) all += r
        return all.withIndex()
            .sortedWith(compareByDescending<IndexedValue<YtResult>> { fit(it.value, queryKey) }.thenBy { it.index })
            .map { it.value }
    }
}
