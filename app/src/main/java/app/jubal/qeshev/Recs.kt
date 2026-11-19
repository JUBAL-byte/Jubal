package app.jubal.qeshev

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Recommendations, built only from what you listened to on this phone. */
object Recs {
    data class Rec(val track: Track, val because: String)

    /**
     * Mixes YouTube's "up next" suggestions for the last things you played,
     * leaving out what you already heard or saved.
     */
    fun forYou(kinds: Set<Kind>, limit: Int): List<Rec> {
        val seeds = Store.relatedSets().filter { it.seedKind in kinds }.take(8)
        if (seeds.isEmpty()) return emptyList()
        val skip = HashSet<String>()
        skip += Store.recentIds(150)
        Store.favorites().forEach { skip += it.id }
        val out = ArrayList<Rec>()
        val seen = HashSet<String>()
        var round = 0
        while (out.size < limit && round < 20) {
            var added = false
            for (seed in seeds) {
                val t = seed.items.getOrNull(round) ?: continue
                added = true
                if (t.id in skip || !seen.add(t.id)) continue
                out += Rec(t, seed.seedTitle)
                if (out.size >= limit) break
            }
            if (!added) break
            round++
        }
        return out
    }

    private val lock = Mutex()
    private var episodes: List<Track>? = null
    private var episodesTime = 0L
    private var episodesKey = ""

    fun cachedEpisodes(): List<Track>? = episodes

    /** The newest episodes from the podcasts you follow, refreshed at most every 30 minutes. */
    suspend fun newEpisodes(force: Boolean = false): List<Track> = lock.withLock {
        val subs = Store.subscriptions()
        val key = subs.joinToString("|") { it.feedUrl }
        val fresh = System.currentTimeMillis() - episodesTime < 30 * 60 * 1000L
        episodes?.let { if (fresh && !force && key == episodesKey) return@withLock it }
        val loaded = withContext(Dispatchers.IO) {
            coroutineScope {
                subs.take(15).map { sub ->
                    async {
                        runCatching {
                            val feed = Podcasts.loadFeed(sub.feedUrl)
                            feed.episodes.sortedByDescending { it.date }.take(4).map { it.toTrack(feed.title, sub.feedUrl) }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }
        val result = loaded.filter { !Store.isDone(it.id) }.sortedByDescending { it.date }.take(15)
        episodes = result
        episodesTime = System.currentTimeMillis()
        episodesKey = key
        result
    }
}
