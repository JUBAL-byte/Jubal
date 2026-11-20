package app.jubal.qeshev

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.min
import kotlin.math.pow

/**
 * Recommendations, built only on this phone, in the spirit of YouTube and Spotify:
 *
 * - What counts is what you chose: playing something yourself, listening past 30 seconds,
 *   finishing it, liking it, adding it to a playlist, saving it, following.
 *   Things that just played next (a queue, a playlist, autoplay) teach nothing.
 * - Skipping within 30 seconds counts against an item and its artist.
 * - Candidates come from YouTube's "up next" for the items you chose (people who play X also play Y).
 *   Items suggested by several of your favorites rank higher. Older signals fade over time.
 * - Most slots go to the best matches, a few to artists you haven't heard yet (explore).
 */
object Recs {
    data class Rec(val track: Track, val because: String)
    data class ArtistScore(val artist: ArtistRef, val score: Double)

    private const val DAY = 86_400_000.0

    private fun decay(time: Long, halfLifeDays: Double = 45.0): Double {
        if (time <= 0) return 1.0
        val days = (System.currentTimeMillis() - time) / DAY
        return 0.5.pow(days.coerceAtLeast(0.0) / halfLifeDays)
    }

    /** How much the listener cares about each item, from their own actions only. */
    private fun scores(kinds: Set<Kind>): Map<String, Pair<Track, Double>> {
        val out = HashMap<String, Pair<Track, Double>>()
        fun add(t: Track, value: Double) {
            if (t.kind !in kinds) return
            val old = out[t.id]
            out[t.id] = t to ((old?.second ?: 0.0) + value)
        }
        for (st in Store.stats()) {
            var s = 2.0 * min(st.active, 4)
            if (st.active > 0) s += 1.0 * min(st.completes, 4)
            if (st.active > 0 && st.listenedMs > 30_000) s += 0.5
            s -= 3.0 * min(st.skips, 3)
            if (s != 0.0) add(st.track, s * decay(st.last))
        }
        Store.favorites().forEach { add(it, 5.0) }
        Store.folders().forEach { f -> f.tracks.forEach { add(it, 3.0) } }
        Store.downloads().forEach { add(it.track, 2.0) }
        return out
    }

    private fun artistOf(t: Track): ArtistRef = ArtistRef(t.artist, t.artistUrl)

    /**
     * Music you chose yourself, as lowercase "title artist" text with a weight,
     * so search can offer categories only to people who listen to them.
     * A followed artist weighs as much as two songs.
     */
    fun chosenMusic(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for ((t, s) in scores(setOf(Kind.MUSIC)).values) {
            if (s > 0.5) out += "${t.title} ${t.artist}".lowercase() to 1
        }
        Store.followedArtists().forEach { out += it.name.lowercase() to 2 }
        return out
    }

    /** The artists you listen to most, followed artists first. */
    fun favoriteArtists(limit: Int): List<ArtistScore> {
        val byKey = LinkedHashMap<String, ArtistScore>()
        Store.followedArtists().forEach { byKey[it.key] = ArtistScore(it, 10.0) }
        for ((t, s) in scores(setOf(Kind.MUSIC)).values) {
            if (t.artist.isBlank()) continue
            val a = artistOf(t)
            val old = byKey[a.key]
            val keep = if (old != null && old.artist.url.isNotBlank()) old.artist else a
            byKey[a.key] = ArtistScore(keep, (old?.score ?: 0.0) + s)
        }
        return byKey.values.filter { it.score > 0.5 }.sortedByDescending { it.score }.take(limit)
    }

    /** "Fans also like": artists that YouTube keeps suggesting next to your favorite artists. */
    fun similarArtists(limit: Int): List<ArtistRef> {
        val top = favoriteArtists(8)
        val topKeys = top.map { it.artist.key }.toSet()
        val count = HashMap<String, Pair<ArtistRef, Double>>()
        val music = scores(setOf(Kind.MUSIC))
        for ((id, pair) in music) {
            val (seed, seedScore) = pair
            if (seedScore <= 0 || artistOf(seed).key !in topKeys) continue
            val set = Store.related(id) ?: continue
            set.items.forEachIndexed { i, t ->
                if (t.artist.isBlank()) return@forEachIndexed
                val a = artistOf(t)
                if (a.key in topKeys) return@forEachIndexed
                val old = count[a.key]
                count[a.key] = (old?.first ?: a) to ((old?.second ?: 0.0) + seedScore / (1 + i * 0.3))
            }
        }
        return count.values.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    /** Artists YouTube suggests next to this artist's songs that you played ("fans also like"). */
    fun similarTo(artist: ArtistRef, limit: Int): List<ArtistRef> {
        val key = artist.key
        val count = HashMap<String, Pair<ArtistRef, Double>>()
        for (st in Store.stats()) {
            if (artistOf(st.track).key != key) continue
            val set = Store.related(st.track.id) ?: continue
            set.items.forEachIndexed { i, t ->
                if (t.artist.isBlank()) return@forEachIndexed
                val a = artistOf(t)
                if (a.key == key) return@forEachIndexed
                val old = count[a.key]
                count[a.key] = (old?.first ?: a) to ((old?.second ?: 0.0) + 1.0 / (1 + i * 0.3))
            }
        }
        return count.values.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    /** Songs or videos you're likely to enjoy next. */
    fun forYou(kinds: Set<Kind>, limit: Int): List<Rec> {
        val sc = scores(kinds)
        val seeds = sc.values.filter { it.second > 0 }.sortedByDescending { it.second }.take(15)
        if (seeds.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val skip = HashSet<String>()
        sc.keys.forEach { skip += it }
        for (st in Store.stats()) {
            if (st.skips > 0 || now - st.last < 14 * DAY) skip += st.track.id
        }
        val artistSkips = HashMap<String, Int>()
        Store.stats().forEach { if (it.skips > 0) artistSkips.merge(artistOf(it.track).key, it.skips) { x, y -> x + y } }

        data class Cand(val track: Track, var score: Double, var because: String, var best: Double)
        val cands = LinkedHashMap<String, Cand>()
        for ((seed, seedScore) in seeds) {
            val set = Store.related(seed.id) ?: continue
            set.items.forEachIndexed { i, t ->
                if (t.id in skip || t.kind !in kinds) return@forEachIndexed
                val add = seedScore / (1 + i * 0.25)
                val c = cands.getOrPut(t.id) { Cand(t, 0.0, seed.title, 0.0) }
                c.score += add
                if (add > c.best) {
                    c.best = add
                    c.because = seed.title
                }
            }
        }
        cands.values.forEach { c ->
            val penalty = artistSkips[artistOf(c.track).key] ?: 0
            if (penalty > 0) c.score /= (1 + penalty)
        }

        val ranked = cands.values.sortedByDescending { it.score }
        val known = favoriteArtists(12).map { it.artist.key }.toSet()
        val perArtist = HashMap<String, Int>()
        val exploit = ranked.filter { artistOf(it.track).key in known }.toMutableList()
        val explore = ranked.filter { artistOf(it.track).key !in known }.toMutableList()
        val out = ArrayList<Rec>()
        var slot = 0
        while (out.size < limit && (exploit.isNotEmpty() || explore.isNotEmpty())) {
            // Every third slot tries something new; the rest go to the best matches.
            val pool = if ((slot % 3 == 2 && explore.isNotEmpty()) || exploit.isEmpty()) explore else exploit
            val c = pool.removeAt(0)
            slot++
            val key = artistOf(c.track).key
            if ((perArtist[key] ?: 0) >= 2) continue
            perArtist[key] = (perArtist[key] ?: 0) + 1
            out += Rec(c.track, c.because)
        }
        return out
    }

    // ------------------------------------------------------------------ albums

    private val albumLock = Mutex()

    fun cachedAlbums(): List<YtList>? {
        val (time, value) = Store.cache("albums") ?: return null
        if (System.currentTimeMillis() - time > 12 * 3600_000L) return null
        return runCatching {
            val a = JSONArray(value)
            (0 until a.length()).map { YtList.fromJson(a.getJSONObject(it)) }
        }.getOrNull()
    }

    /** Albums by your favorite artists and by artists their fans also like, that you haven't played yet. */
    suspend fun recommendedAlbums(limit: Int, force: Boolean = false): List<YtList> = albumLock.withLock {
        if (!force) cachedAlbums()?.let { return@withLock it }
        val favorite = favoriteArtists(4).map { it.artist }
        val similar = similarArtists(3)
        val artists = (favorite + similar).filter { it.url.isNotBlank() || it.name.isNotBlank() }
        if (artists.isEmpty()) return@withLock emptyList()
        val played = Store.contexts().filter { it.type == PlayContext.ALBUM || it.type == PlayContext.YT_LIST }.map { it.ref }.toSet()
        val saved = Store.savedAlbums().map { it.url }.toSet()
        val perArtist = withContext(Dispatchers.IO) {
            coroutineScope {
                artists.map { a ->
                    async {
                        runCatching {
                            val ref = if (a.url.isNotBlank()) a else YouTube.findArtist(a.name) ?: return@runCatching emptyList<YtList>()
                            val page = YouTube.artistPage(ref.url, ref.name)
                            (page.albums + page.singles.take(1)).filter { it.url !in played && it.url !in saved }.take(3)
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
        }
        val out = ArrayList<YtList>()
        var round = 0
        while (out.size < limit && round < 4) {
            perArtist.forEach { list -> list.getOrNull(round)?.let { if (out.size < limit) out += it } }
            round++
        }
        val a = JSONArray()
        out.forEach { a.put(it.toJson()) }
        Store.setCache("albums", a.toString())
        out
    }

    // ------------------------------------------------------------------ podcasts

    private val podLock = Mutex()

    fun cachedPodcasts(): List<PodcastRef>? {
        val (time, value) = Store.cache("pods") ?: return null
        if (System.currentTimeMillis() - time > 12 * 3600_000L) return null
        return runCatching {
            val a = JSONArray(value)
            (0 until a.length()).map { PodcastRef.fromJson(a.getJSONObject(it)) }
        }.getOrNull()
    }

    /** The categories you listen to, from podcasts you follow and episodes you chose to play. */
    fun podcastGenres(): List<String> {
        val weight = HashMap<String, Double>()
        Store.subscriptions().forEach { s ->
            (Store.podcastMeta(s.feedUrl)?.genreIds ?: s.genreIds).forEach { weight.merge(it, 3.0) { x, y -> x + y } }
        }
        Store.stats().filter { it.track.kind == Kind.PODCAST && it.active > 0 }.forEach { st ->
            Store.podcastMeta(st.track.feedUrl)?.genreIds?.forEach { weight.merge(it, 1.0 * decay(st.last)) { x, y -> x + y } }
        }
        return weight.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Popular shows in your categories that you don't follow yet. */
    suspend fun recommendedPodcasts(limit: Int, force: Boolean = false): List<PodcastRef> = podLock.withLock {
        if (!force) cachedPodcasts()?.let { return@withLock it }
        val genres = podcastGenres().take(2)
        if (genres.isEmpty()) return@withLock emptyList()
        val following = Store.subscriptions().map { it.feedUrl }.toSet()
        val lists = withContext(Dispatchers.IO) {
            coroutineScope {
                genres.map { g -> async { runCatching { Podcasts.topInGenre(g, 30) }.getOrDefault(emptyList()) } }.awaitAll()
            }
        }
        val out = ArrayList<PodcastRef>()
        val seen = HashSet<String>()
        var round = 0
        while (out.size < limit && round < 30) {
            lists.forEach { l ->
                l.getOrNull(round)?.let { p ->
                    if (p.feedUrl !in following && seen.add(p.feedUrl) && out.size < limit) out += p.ref()
                }
            }
            round++
        }
        val a = JSONArray()
        out.forEach { a.put(it.toJson()) }
        Store.setCache("pods", a.toString())
        out
    }

    /** Shows in the same categories as these, for a podcaster's page. */
    suspend fun similarPodcasts(genreIds: List<String>, exclude: Set<String>, limit: Int): List<PodcastResult> =
        withContext(Dispatchers.IO) {
            val g = genreIds.filter { it != "26" }.take(2)
            if (g.isEmpty()) return@withContext emptyList()
            val lists = coroutineScope { g.map { async { runCatching { Podcasts.topInGenre(it, 30) }.getOrDefault(emptyList()) } }.awaitAll() }
            val out = ArrayList<PodcastResult>()
            val seen = HashSet<String>()
            var round = 0
            while (out.size < limit && round < 30) {
                lists.forEach { l ->
                    l.getOrNull(round)?.let { p -> if (p.feedUrl !in exclude && seen.add(p.feedUrl) && out.size < limit) out += p }
                }
                round++
            }
            out
        }

    // ------------------------------------------------------------------ new episodes

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
                            feed.episodes.sortedByDescending { it.date }.take(4)
                                .map { it.toTrack(feed.title, sub.feedUrl, sub.author.ifBlank { feed.author }) }
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
