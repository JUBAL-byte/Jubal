package app.jubal.qeshev

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Everything the app remembers: progress, history, favorites, folders, subscriptions, downloads. */
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
    private var autoplay = true
    private var speed = 1f

    fun init(context: Context) {
        prefs = context.getSharedPreferences("qeshev", Context.MODE_PRIVATE)
        runCatching {
            val o = JSONObject(prefs.getString("positions", "{}") ?: "{}")
            for (key in o.keys()) {
                val a = o.optJSONArray(key) ?: continue
                positions[key] = longArrayOf(a.optLong(0), a.optLong(1))
            }
        }
        runCatching {
            val a = JSONArray(prefs.getString("done", "[]") ?: "[]")
            for (i in 0 until a.length()) done += a.optString(i)
        }
        runCatching {
            val a = JSONArray(prefs.getString("history", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                history += HistoryEntry(Track.fromJson(o.getJSONObject("t")), o.optLong("time"))
            }
        }
        runCatching { favorites += Track.listFromJson(JSONArray(prefs.getString("favorites", "[]") ?: "[]")) }
        runCatching {
            val a = JSONArray(prefs.getString("folders", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                folders += Folder(
                    o.optString("id"), o.optString("name"),
                    Track.listFromJson(o.optJSONArray("tracks")), o.optLong("created"),
                )
            }
        }
        runCatching {
            val a = JSONArray(prefs.getString("subs", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                subs += PodcastRef(o.optString("title"), o.optString("author"), o.optString("feedUrl"))
            }
        }
        runCatching {
            val a = JSONArray(prefs.getString("downloads", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                downloads += Download(
                    Track.fromJson(o.getJSONObject("t")), o.optString("uri"), o.optString("mime"), o.optLong("time"),
                )
            }
        }
        runCatching {
            val a = JSONArray(prefs.getString("related", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val set = RelatedSet(
                    o.optString("seed"), o.optString("title"), Kind.of(o.optString("kind")),
                    Track.listFromJson(o.optJSONArray("items")), o.optLong("time"),
                )
                related[set.seedId] = set
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

    private fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        changed()
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
        // No change notification here: positions are saved every few seconds while playing,
        // and redrawing lists that often would steal keypad focus.
    }

    // ------------------------------------------------------------------ history

    @Synchronized fun addHistory(track: Track) {
        history.removeAll { it.track.id == track.id }
        history.add(0, HistoryEntry(track, System.currentTimeMillis()))
        while (history.size > 300) history.removeAt(history.size - 1)
        saveHistory()
    }

    @Synchronized fun history(): List<HistoryEntry> = history.toList()

    @Synchronized fun recentIds(count: Int): Set<String> = history.take(count).map { it.track.id }.toSet()

    @Synchronized fun clearHistory() {
        history.clear()
        saveHistory()
    }

    @Synchronized fun removeHistory(id: String) {
        history.removeAll { it.track.id == id }
        saveHistory()
    }

    /** Episodes and long videos that were started but not finished, newest first. */
    @Synchronized fun inProgress(limit: Int): List<Track> =
        history.asSequence()
            .map { it.track }
            .filter { it.kind != Kind.MUSIC && it.id !in done && (positions[it.id]?.get(0) ?: 0) > 30_000 }
            .take(limit)
            .toList()

    private fun saveHistory() {
        val a = JSONArray()
        history.forEach { a.put(JSONObject().put("t", it.track.toJson()).put("time", it.time)) }
        put("history", a.toString())
    }

    // ------------------------------------------------------------------ favorites

    @Synchronized fun favorites(): List<Track> = favorites.toList()

    @Synchronized fun isFavorite(id: String): Boolean = favorites.any { it.id == id }

    /** Returns the new state. */
    @Synchronized fun toggleFavorite(track: Track): Boolean {
        val wasFavorite = favorites.removeAll { it.id == track.id }
        if (!wasFavorite) favorites.add(0, track)
        put("favorites", Track.listToJson(favorites).toString())
        return !wasFavorite
    }

    // ------------------------------------------------------------------ folders

    @Synchronized fun folders(): List<Folder> = folders.toList()

    @Synchronized fun folder(id: String): Folder? = folders.firstOrNull { it.id == id }

    @Synchronized fun createFolder(name: String, tracks: List<Track> = emptyList()): Folder {
        val folder = Folder(UUID.randomUUID().toString(), name.trim().ifEmpty { "תיקייה חדשה" }, tracks.distinctBy { it.id }, System.currentTimeMillis())
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
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("created", it.created)
                    .put("tracks", Track.listToJson(it.tracks))
            )
        }
        put("folders", a.toString())
    }

    // ------------------------------------------------------------------ podcast subscriptions

    @Synchronized fun subscriptions(): List<PodcastRef> = subs.toList()

    @Synchronized fun isSubscribed(feedUrl: String): Boolean = subs.any { it.feedUrl == feedUrl }

    @Synchronized fun subscribe(ref: PodcastRef) {
        if (isSubscribed(ref.feedUrl)) return
        subs.add(0, ref)
        saveSubs()
    }

    @Synchronized fun unsubscribe(feedUrl: String) {
        subs.removeAll { it.feedUrl == feedUrl }
        saveSubs()
    }

    private fun saveSubs() {
        val a = JSONArray()
        subs.forEach { a.put(JSONObject().put("title", it.title).put("author", it.author).put("feedUrl", it.feedUrl)) }
        put("subs", a.toString())
    }

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

    // ------------------------------------------------------------------ related items (recommendations)

    @Synchronized fun setRelated(set: RelatedSet) {
        if (set.items.isEmpty()) return
        related.remove(set.seedId)
        related[set.seedId] = set
        while (related.size > 24) related.remove(related.keys.first())
        val a = JSONArray()
        related.values.forEach {
            a.put(
                JSONObject().put("seed", it.seedId).put("title", it.seedTitle).put("kind", it.seedKind.code)
                    .put("time", it.time).put("items", Track.listToJson(it.items.take(20)))
            )
        }
        put("related", a.toString())
    }

    @Synchronized fun related(seedId: String): RelatedSet? = related[seedId]

    @Synchronized fun relatedSets(): List<RelatedSet> = related.values.sortedByDescending { it.time }

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
