package app.jubal.keshev

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Something that can be played. kind is "url" (direct audio or a saved file) or "yt" (a YouTube video page). */
data class PlayRef(
    val mediaId: String,
    val title: String,
    val artist: String,
    val kind: String,
    val ref: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mediaId", mediaId).put("title", title).put("artist", artist)
        .put("kind", kind).put("ref", ref)

    companion object {
        fun fromJson(o: JSONObject) = PlayRef(
            o.optString("mediaId"), o.optString("title"), o.optString("artist"),
            o.optString("kind", "url"), o.optString("ref"),
        )
    }
}

data class Subscription(val title: String, val author: String, val feedUrl: String)

data class SavedFile(
    val mediaId: String,
    val title: String,
    val artist: String,
    val uri: String,
    val mime: String,
    val time: Long,
)

/** Everything the app remembers, kept in one small preferences file. */
object Store {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    private val positions = HashMap<String, Long>()
    private val done = HashSet<String>()
    private val subs = ArrayList<Subscription>()
    private val files = ArrayList<SavedFile>()
    private var last: PlayRef? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("keshev", Context.MODE_PRIVATE)
        runCatching {
            val o = JSONObject(prefs.getString("positions", "{}") ?: "{}")
            for (key in o.keys()) positions[key] = o.optLong(key)
        }
        runCatching {
            val a = JSONArray(prefs.getString("done", "[]") ?: "[]")
            for (i in 0 until a.length()) done += a.optString(i)
        }
        runCatching {
            val a = JSONArray(prefs.getString("subs", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                subs += Subscription(o.optString("title"), o.optString("author"), o.optString("feedUrl"))
            }
        }
        runCatching {
            val a = JSONArray(prefs.getString("files", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                files += SavedFile(
                    o.optString("mediaId"), o.optString("title"), o.optString("artist"),
                    o.optString("uri"), o.optString("mime"), o.optLong("time"),
                )
            }
        }
        runCatching {
            prefs.getString("last", null)?.let { last = PlayRef.fromJson(JSONObject(it)) }
        }
    }

    fun addListener(l: () -> Unit) = synchronized(this) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(this) { listeners -= l }
    private fun changed() {
        main.post {
            val copy = synchronized(this) { listeners.toList() }
            copy.forEach { it() }
        }
    }

    // Listening progress

    @Synchronized fun position(mediaId: String): Long = if (mediaId in done) 0 else positions[mediaId] ?: 0

    @Synchronized fun isDone(mediaId: String) = mediaId in done

    @Synchronized fun savePosition(mediaId: String, ms: Long) {
        positions[mediaId] = ms
        done -= mediaId
        prefs.edit()
            .putString("positions", JSONObject(positions as Map<*, *>).toString())
            .putString("done", JSONArray(done).toString())
            .apply()
    }

    @Synchronized fun markDone(mediaId: String) {
        positions -= mediaId
        done += mediaId
        prefs.edit()
            .putString("positions", JSONObject(positions as Map<*, *>).toString())
            .putString("done", JSONArray(done).toString())
            .apply()
    }

    // Library

    @Synchronized fun subscriptions(): List<Subscription> = subs.toList()

    @Synchronized fun isSubscribed(feedUrl: String) = subs.any { it.feedUrl == feedUrl }

    @Synchronized fun subscribe(s: Subscription) {
        if (isSubscribed(s.feedUrl)) return
        subs += s
        saveSubs()
    }

    @Synchronized fun unsubscribe(feedUrl: String) {
        subs.removeAll { it.feedUrl == feedUrl }
        saveSubs()
    }

    private fun saveSubs() {
        val a = JSONArray()
        subs.forEach { a.put(JSONObject().put("title", it.title).put("author", it.author).put("feedUrl", it.feedUrl)) }
        prefs.edit().putString("subs", a.toString()).apply()
        changed()
    }

    // Saved files

    @Synchronized fun savedFiles(): List<SavedFile> = files.sortedByDescending { it.time }

    @Synchronized fun savedFileFor(mediaId: String): SavedFile? = files.firstOrNull { it.mediaId == mediaId }

    @Synchronized fun addSavedFile(f: SavedFile) {
        files.removeAll { it.mediaId == f.mediaId }
        files += f
        saveFiles()
    }

    @Synchronized fun removeSavedFile(mediaId: String) {
        files.removeAll { it.mediaId == mediaId }
        saveFiles()
    }

    private fun saveFiles() {
        val a = JSONArray()
        files.forEach {
            a.put(
                JSONObject().put("mediaId", it.mediaId).put("title", it.title).put("artist", it.artist)
                    .put("uri", it.uri).put("mime", it.mime).put("time", it.time)
            )
        }
        prefs.edit().putString("files", a.toString()).apply()
        changed()
    }

    // Last played, for "continue listening"

    @Synchronized fun last(): PlayRef? = last

    @Synchronized fun setLast(ref: PlayRef) {
        last = ref
        prefs.edit().putString("last", ref.toJson().toString()).apply()
        changed()
    }
}
