package app.jubal.qeshev

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Everything the app remembers: progress, history, likes, playlists, follows, downloads and listening signals. */
object Store {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    @Volatile private var notifyPosted = false

    private val positions = LinkedHashMap<String, LongArray>()
    private val done = LinkedHashSet<String>()
    private val history = ArrayList<HistoryEntry>()
    private val favorites = ArrayList<Track>()
    private val folders = ArrayList<Folder>()
    private val subs = ArrayList<PodcastRef>()
    private val downloads = ArrayList<Download>()
    private val related = LinkedHashMap<String, RelatedSet>()
    private val stats = LinkedHashMap<String, Stat>()
    private val contexts = ArrayList<Pair<PlayContext, Long>>()
    private val savedAlbums = ArrayList<YtList>()
    private val followedArtists = ArrayList<ArtistRef>()
    private val searches = ArrayList<String>()
    private val podMeta = LinkedHashMap<String, PodcastRef>()
    private var autoplay = true
    private var speed = 1f

    private inline fun <T> load(key: String, fallback: String, block: (String) -> T) {
        runCatching { block(prefs.getString(key, fallback) ?: fallback) }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("qeshev", Context.MODE_PRIVATE)
        load("positions", "{}") { s ->
            val o = JSONObject(s)
            for (key in o.keys()) {
                val a = o.optJSONArray(key) ?: continue
                positions[key] = longArrayOf(a.optLong(0), a.optLong(1))
            }
        }
        load("done", "[]") { s -> val a = JSONArray(s); for (i in 0 until a.length()) done += a.optString(i) }
        load("history", "[]") { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                history += HistoryEntry(Track.fromJson(o.getJSONObject("t")), o.optLong("time"), o.optBoolean("a", false))
            }
        }
        load("favorites", "[]") { s -> favorites += Track.listFromJson(JSONArray(s)) }
        load("folders", "[]") { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                folders += Folder(o.optString("id"), o.optString("name"), Track.listFromJson(o.optJSONArray("tracks")), o.optLong("created"))
            }
        }
        load("subs", "[]") { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) subs += PodcastRef.fromJson(a.getJSONObject(i))
        }
        load("downloads", "[]") { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                downloads += Download(Track.fromJson(o.getJSONObject("t")), o.optString("uri"), o.optString("mime"), o.optLong("time"))
            }
        }
        load("related", "[]") { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val set = RelatedSet(
                    o.optString("seed"), o.optString("title"), Kind.of(o.optString("kind")),
                    Track.listFromJson(o.optJSONArray("items")), o.optLong("time"),
                )
                related[set.seedId] = set
            }
        }
        load("stats", "[]") { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val st = Stat.fromJson(a.getJSONObject(i))
                stats[st.track.id] = st
            }
        }
        load("contexts", "[]") { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                contexts += PlayContext.fromJson(o.getJSONObject("c")) to o.optLong("time")
            }
        }
        load("albums", "[]") { s -> val a = JSONArray(s); for (i in 0 until a.length()) savedAlbums += YtList.fromJson(a.getJSONObject(i)) }
        load("artists", "[]") { s -> val a = JSONArray(s); for (i in 0 until a.length()) followedArtists += ArtistRef.fromJson(a.getJSONObject(i)) }
        load("searches", "[]") { s -> val a = JSONArray(s); for (i in 0 until a.length()) searches += a.optString(i) }
        load("podmeta", "[]") { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val r = PodcastRef.fromJson(a.getJSONObject(i))
                podMeta[r.feedUrl] = r
            }
        }
        autoplay = prefs.getBoolean("autoplay", true)
        speed = prefs.getFloat("speed", 1f)
    }

    // ------------------------------------------------------------------ change listeners

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    private fun changed() {
        if (notifyPosted) return
        notifyPosted = true
        main.post {
            notifyPosted = false
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { it() }
        }
    }

    private fun put(key: String, value: String, notify: Boolean = true) {
        prefs.edit().putString(key, value).apply()
        if (notify) changed()
    }

    // ------------------------------------------------------------------ listening progress

    @Synchronized fun position(id: String): Long = if (id in done) 0 else positions[id]?.get(0) ?: 0

    @Synchronized fun isDone(id: String): Boolean = id in done

    /** 0..1000, or -1 when there is nothing to show. */
    @Synchronized fun progress(id: String, fallbackDurationSec: Long): Int {
        if (id in done) return 1000
        val p = positions[id] ?: return -1
        val duration = if (p[1] > 0) p[1] else fallbackDurationSec * 1000
        if (duration <= 0 || p[0] <= 0) return -1
        return ((p[0] * 1000) / duration).toInt().coerceIn(0, 1000)
    }

    @Synchronized fun remainingMs(id: String, fallbackDurationSec: Long): Long {
        val p = positions[id] ?: return -1
        val duration = if (p[1] > 0) p[1] else fallbackDurationSec * 1000
        return if (duration > 0) (duration - p[0]).coerceAtLeast(0) else -1
    }

    @Synchronized fun savePosition(id: String, positionMs: Long, durationMs: Long) {
        positions.remove(id)
        positions[id] = longArrayOf(positionMs, durationMs)
        while (positions.size > 600) positions.remove(positions.keys.first())
        done -= id
        savePositions()
    }

    @Synchronized fun clearPosition(id: String) {
        positions -= id
        done -= id
        savePositions()
    }

    @Synchronized fun markDone(id: String) {
        positions -= id
        done += id
        while (done.size > 1500) done.remove(done.first())
        savePositions()
    }

    private fun savePositions() {
        val o = JSONObject()
        positions.forEach { (k, v) -> o.put(k, JSONArray().put(v[0]).put(v[1])) }
        prefs.edit()
            .putString("positions", o.toString())
            .putString("done", JSONArray(done.toList()).toString())
            .apply()
        // No change notification: positions are saved every few seconds while playing,
        // and redrawing lists that often would steal keypad focus.
    }

    // ------------------------------------------------------------------ history and listening signals

    /** Called by the player when something starts. [active] = the listener picked it themselves. */
    @Synchronized fun recordStart(track: Track, active: Boolean) {
        history.removeAll { it.track.id == track.id }
        history.add(0, HistoryEntry(track, System.currentTimeMillis(), active))
        while (history.size > 300) history.removeAt(history.size - 1)
        val old = stats[track.id] ?: Stat(track)
        stats.remove(track.id)
        stats[track.id] = old.copy(
            track = track,
            active = old.active + if (active) 1 else 0,
            passive = old.passive + if (active) 0 else 1,
            last = System.currentTimeMillis(),
        )
        trimStats()
        saveStats()
        saveHistory()
    }

    /** Called by the player when something stops: how long it was heard, and whether it was finished or skipped. */
    @Synchronized fun recordEnd(track: Track, listenedMs: Long, completed: Boolean, skippedEarly: Boolean) {
        val old = stats[track.id] ?: return
        stats[track.id] = old.copy(
            listenedMs = old.listenedMs + listenedMs.coerceAtLeast(0),
            completes = old.completes + if (completed) 1 else 0,
            skips = old.skips + if (skippedEarly) 1 else 0,
        )
        saveStats()
    }

    @Synchronized fun stat(id: String): Stat? = stats[id]

    @Synchronized fun stats(): List<Stat> = stats.values.toList()

    private fun trimStats() {
        while (stats.size > 800) stats.remove(stats.keys.first())
    }

    private fun saveStats() {
        val a = JSONArray()
        stats.values.forEach { a.put(it.toJson()) }
        put("stats", a.toString(), notify = false)
    }

    @Synchronized fun history(): List<HistoryEntry> = history.toList()

    @Synchronized fun recentIds(count: Int): Set<String> = history.take(count).map { it.track.id }.toSet()

    @Synchronized fun clearHistory() {
        history.clear()
        contexts.clear()
        saveHistory()
        saveContexts()
    }

    @Synchronized fun removeHistory(id: String) {
        history.removeAll { it.track.id == id }
        saveHistory()
    }

    /** Started but not finished, newest first. Something the player moved on to by itself needs a few minutes to count. */
    @Synchronized fun inProgress(kinds: Set<Kind>, limit: Int): List<Track> =
        history.asSequence()
            .filter { it.track.kind in kinds && it.track.id !in done }
            .filter { (positions[it.track.id]?.get(0) ?: 0) > if (it.active) 30_000 else 180_000 }
            .map { it.track }
            .take(limit)
            .toList()

    private fun saveHistory() {
        val a = JSONArray()
        history.forEach { a.put(JSONObject().put("t", it.track.toJson()).put("time", it.time).put("a", it.active)) }
        put("history", a.toString())
    }

    /** Wipes everything recommendations learned, without touching likes, playlists or follows. */
    @Synchronized fun resetRecommendations() {
        stats.clear()
        related.clear()
        saveStats()
        put("related", "[]")
    }

    // ------------------------------------------------------------------ recently played albums, playlists, artists

    @Synchronized fun addContext(ctx: PlayContext) {
        contexts.removeAll { it.first.key == ctx.key }
        contexts.add(0, ctx to System.currentTimeMillis())
        while (contexts.size > 40) contexts.removeAt(contexts.size - 1)
        saveContexts()
    }

    @Synchronized fun contexts(): List<PlayContext> = contexts.map { it.first }

    private fun saveContexts() {
        val a = JSONArray()
        contexts.forEach { a.put(JSONObject().put("c", it.first.toJson()).put("time", it.second)) }
        put("contexts", a.toString())
    }

    // ------------------------------------------------------------------ liked

    @Synchronized fun favorites(): List<Track> = favorites.toList()

    @Synchronized fun isFavorite(id: String): Boolean = favorites.any { it.id == id }

    /** Returns the new state. */
    @Synchronized fun toggleFavorite(track: Track): Boolean {
        val wasFavorite = favorites.removeAll { it.id == track.id }
        if (!wasFavorite) favorites.add(0, track)
        put("favorites", Track.listToJson(favorites).toString())
        return !wasFavorite
    }

    // ------------------------------------------------------------------ playlists (called folders inside)

    @Synchronized fun folders(): List<Folder> = folders.toList()

    @Synchronized fun folder(id: String): Folder? = folders.firstOrNull { it.id == id }

    @Synchronized fun inAnyFolder(id: String): Boolean = folders.any { f -> f.tracks.any { it.id == id } }

    @Synchronized fun createFolder(name: String, tracks: List<Track> = emptyList()): Folder {
        val folder = Folder(UUID.randomUUID().toString(), name.trim().ifEmpty { "פלייליסט חדש" }, tracks.distinctBy { it.id }, System.currentTimeMillis())
        folders.add(0, folder)
        saveFolders()
        return folder
    }

    @Synchronized fun renameFolder(id: String, name: String) {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0 || name.isBlank()) return
        folders[i] = folders[i].copy(name = name.trim())
        saveFolders()
    }

    @Synchronized fun deleteFolder(id: String) {
        folders.removeAll { it.id == id }
        saveFolders()
    }

    /** Returns false when the track was already there. */
    @Synchronized fun addToFolder(id: String, track: Track): Boolean {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0) return false
        val f = folders[i]
        if (f.tracks.any { it.id == track.id }) return false
        folders[i] = f.copy(tracks = f.tracks + track)
        saveFolders()
        return true
    }

    @Synchronized fun removeFromFolder(id: String, trackId: String) {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0) return
        folders[i] = folders[i].copy(tracks = folders[i].tracks.filterNot { it.id == trackId })
        saveFolders()
    }

    private fun saveFolders() {
        val a = JSONArray()
        folders.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("created", it.created).put("tracks", Track.listToJson(it.tracks)))
        }
        put("folders", a.toString())
    }

    // ------------------------------------------------------------------ saved albums and followed artists

    @Synchronized fun savedAlbums(): List<YtList> = savedAlbums.toList()

    @Synchronized fun isAlbumSaved(url: String): Boolean = savedAlbums.any { it.url == url }

    @Synchronized fun toggleAlbum(album: YtList): Boolean {
        val was = savedAlbums.removeAll { it.url == album.url }
        if (!was) savedAlbums.add(0, album)
        val a = JSONArray()
        savedAlbums.forEach { a.put(it.toJson()) }
        put("albums", a.toString())
        return !was
    }

    @Synchronized fun followedArtists(): List<ArtistRef> = followedArtists.toList()

    @Synchronized fun isFollowing(artist: ArtistRef): Boolean = followedArtists.any { it.key == artist.key }

    @Synchronized fun toggleFollow(artist: ArtistRef): Boolean {
        val was = followedArtists.removeAll { it.key == artist.key }
        if (!was) followedArtists.add(0, artist)
        val a = JSONArray()
        followedArtists.forEach { a.put(it.toJson()) }
        put("artists", a.toString())
        return !was
    }

    // ------------------------------------------------------------------ podcasts

    @Synchronized fun subscriptions(): List<PodcastRef> = subs.toList()

    @Synchronized fun isSubscribed(feedUrl: String): Boolean = subs.any { it.feedUrl == feedUrl }

    @Synchronized fun subscribe(ref: PodcastRef) {
        if (isSubscribed(ref.feedUrl)) return
        val full = podMeta[ref.feedUrl]?.let { meta ->
            ref.copy(
                collectionId = if (ref.collectionId > 0) ref.collectionId else meta.collectionId,
                genreIds = ref.genreIds.ifEmpty { meta.genreIds },
                genres = ref.genres.ifEmpty { meta.genres },
                author = ref.author.ifBlank { meta.author },
            )
        } ?: ref
        subs.add(0, full)
        saveSubs()
    }

    @Synchronized fun unsubscribe(feedUrl: String) {
        subs.removeAll { it.feedUrl == feedUrl }
        saveSubs()
    }

    private fun saveSubs() {
        val a = JSONArray()
        subs.forEach { a.put(it.toJson()) }
        put("subs", a.toString())
    }

    /** Directory details (categories, Apple id) for podcasts seen in search, used for "similar podcasts". */
    @Synchronized fun rememberPodcasts(refs: List<PodcastRef>) {
        if (refs.isEmpty()) return
        refs.forEach { r ->
            podMeta.remove(r.feedUrl)
            podMeta[r.feedUrl] = r
        }
        while (podMeta.size > 400) podMeta.remove(podMeta.keys.first())
        val a = JSONArray()
        podMeta.values.forEach { a.put(it.toJson()) }
        put("podmeta", a.toString(), notify = false)
    }

    @Synchronized fun podcastMeta(feedUrl: String): PodcastRef? =
        subs.firstOrNull { it.feedUrl == feedUrl && it.genreIds.isNotEmpty() } ?: podMeta[feedUrl]

    // ------------------------------------------------------------------ downloads

    @Synchronized fun downloads(): List<Download> = downloads.sortedByDescending { it.time }

    @Synchronized fun downloadFor(id: String): Download? = downloads.firstOrNull { it.track.id == id }

    @Synchronized fun addDownload(d: Download) {
        downloads.removeAll { it.track.id == d.track.id }
        downloads += d
        saveDownloads()
    }

    @Synchronized fun removeDownload(id: String) {
        downloads.removeAll { it.track.id == id }
        saveDownloads()
    }

    private fun saveDownloads() {
        val a = JSONArray()
        downloads.forEach {
            a.put(JSONObject().put("t", it.track.toJson()).put("uri", it.uri).put("mime", it.mime).put("time", it.time))
        }
        put("downloads", a.toString())
    }

    // ------------------------------------------------------------------ related items (raw material for recommendations)

    @Synchronized fun setRelated(set: RelatedSet) {
        if (set.items.isEmpty()) return
        related.remove(set.seedId)
        related[set.seedId] = set
        while (related.size > 60) related.remove(related.keys.first())
        val a = JSONArray()
        related.values.forEach {
            a.put(
                JSONObject().put("seed", it.seedId).put("title", it.seedTitle).put("kind", it.seedKind.code)
                    .put("time", it.time).put("items", Track.listToJson(it.items.take(20)))
            )
        }
        put("related", a.toString(), notify = false)
    }

    @Synchronized fun related(seedId: String): RelatedSet? = related[seedId]

    @Synchronized fun relatedSets(): List<RelatedSet> = related.values.sortedByDescending { it.time }

    // ------------------------------------------------------------------ recent searches

    @Synchronized fun recentSearches(): List<String> = searches.toList()

    @Synchronized fun addSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        searches.removeAll { it.equals(t, ignoreCase = true) }
        searches.add(0, t)
        while (searches.size > 10) searches.removeAt(searches.size - 1)
        put("searches", JSONArray(searches).toString())
    }

    @Synchronized fun clearSearches() {
        searches.clear()
        put("searches", "[]")
    }

    // ------------------------------------------------------------------ cached results (recommended albums and podcasts)

    fun cache(key: String): Pair<Long, String>? {
        val time = prefs.getLong("cache_t_$key", 0)
        val value = prefs.getString("cache_v_$key", null) ?: return null
        return time to value
    }

    fun setCache(key: String, value: String) {
        prefs.edit().putLong("cache_t_$key", System.currentTimeMillis()).putString("cache_v_$key", value).apply()
    }

    fun clearCache(key: String) {
        prefs.edit().remove("cache_t_$key").remove("cache_v_$key").apply()
    }

    // ------------------------------------------------------------------ app updates

    fun lastUpdateCheck(): Long = prefs.getLong("update_check", 0)

    fun setLastUpdateCheck(time: Long) {
        prefs.edit().putLong("update_check", time).apply()
    }

    // ------------------------------------------------------------------ playback settings

    @Synchronized fun autoplay(): Boolean = autoplay

    @Synchronized fun setAutoplay(on: Boolean) {
        autoplay = on
        prefs.edit().putBoolean("autoplay", on).apply()
        changed()
    }

    @Synchronized fun speed(): Float = speed

    @Synchronized fun setSpeed(value: Float) {
        speed = value
        prefs.edit().putFloat("speed", value).apply()
    }
}
