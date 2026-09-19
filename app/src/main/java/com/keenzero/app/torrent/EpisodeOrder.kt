package com.keenzero.app.torrent

/**
 * The order the files of a pack are offered in.
 *
 * A plain name sort is wrong for the packs this exists to serve. Lexicographically
 * "Episode 10" precedes "Episode 2", so a ten-part season is listed 1, 10, 11, 2, 3 —
 * and the next-episode offer, which is defined as the neighbour in this order, then
 * hands the viewer episode 10 after episode 1.
 *
 * So the season/episode tag is read out of the name first and ordered numerically. Only
 * when a pair cannot both be read that way does it fall back to a natural name compare,
 * which is the same rule applied to whatever digits the name does carry.
 */
internal object EpisodeOrder {

    /** SxxEyy, and its spelt-out and cross ("2x05") variants. */
    private val SEASON_EPISODE = Regex(
        """(?:s|season)\s*[._\- ]?(\d{1,3})\s*[._\- ]?(?:e|ep|episode|x)\s*[._\- ]?(\d{1,4})""",
        RegexOption.IGNORE_CASE,
    )

    /** An episode with no season beside it: "Episode 4", "Ep.4", "E04", "- 04 -". */
    private val EPISODE_ONLY = Regex(
        """(?:\b|_)(?:e|ep|episode|part|pt)\s*[._\- ]?(\d{1,4})(?:\b|_)""",
        RegexOption.IGNORE_CASE,
    )

    /** Season and episode as read from a name; [season] is null when only an episode showed. */
    private data class Tag(val season: Int?, val episode: Int)

    private fun tagOf(name: String): Tag? {
        // The basename only. A path like "Season 2/03 - title.mkv" would otherwise let
        // the folder's season pair up with a later file's episode.
        val base = name.substringAfterLast('/')
        SEASON_EPISODE.find(base)?.let { m ->
            return Tag(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull() ?: return@let)
        }
        EPISODE_ONLY.find(base)?.let { m ->
            return Tag(null, m.groupValues[1].toIntOrNull() ?: return@let)
        }
        return null
    }

    /**
     * Compare two names as pack entries.
     *
     * Public so both the picker and the next-episode neighbour use one rule; the two
     * disagreeing is what makes an out-of-order offer possible in the first place.
     */
    fun compare(left: String, right: String): Int {
        val a = tagOf(left)
        val b = tagOf(right)
        if (a != null && b != null) {
            // Seasons only rank against seasons. A tagged "S02E01" against a bare
            // "Episode 3" is not a comparison the tag can settle, so the names do it.
            if ((a.season == null) == (b.season == null)) {
                if (a.season != null && b.season != null && a.season != b.season) {
                    return a.season.compareTo(b.season)
                }
                if (a.episode != b.episode) return a.episode.compareTo(b.episode)
            }
        }
        return natural(left, right)
    }

    /** Name order with digit runs compared as numbers rather than character by character. */
    private fun natural(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val lc = left[i]
            val rc = right[j]
            if (lc.isDigit() && rc.isDigit()) {
                val li = run { var k = i; while (k < left.length && left[k].isDigit()) k++; k }
                val rj = run { var k = j; while (k < right.length && right[k].isDigit()) k++; k }
                // Compared as text once the leading zeros are gone, so a run longer than
                // Int can hold still orders correctly instead of overflowing.
                val ln = left.substring(i, li).trimStart('0')
                val rn = right.substring(j, rj).trimStart('0')
                if (ln.length != rn.length) return ln.length.compareTo(rn.length)
                val byValue = ln.compareTo(rn)
                if (byValue != 0) return byValue
                i = li
                j = rj
            } else {
                val byChar = lc.lowercaseChar().compareTo(rc.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (left.length - i).compareTo(right.length - j)
    }
}
