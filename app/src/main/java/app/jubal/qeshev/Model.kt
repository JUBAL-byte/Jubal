package app.jubal.qeshev

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

enum class Kind(val code: String, val label: String) {
    PODCAST("p", "פודקאסט"),
    VIDEO("v", "סרטון"),
    MUSIC("m", "שיר");

    companion object {
        fun of(code: String?): Kind = Kind.entries.firstOrNull { it.code == code } ?: VIDEO
    }
}

/** Anything that can be played: a podcast episode, or the audio of a YouTube video or song. */
data class Track(
    val id: String,
    val kind: Kind,
    val title: String,
    val artist: String,
    val url: String,
    val durationSec: Long = 0,
    val feedUrl: String = "",
    val date: Long = 0,
) {
    val isYouTube: Boolean get() = kind != Kind.PODCAST
    val videoId: String get() = if (isYouTube) id.removePrefix("y:") else ""

    /** The address handed to the player. YouTube items are turned into real audio links only when played. */
    fun playUri(): String = if (isYouTube) "qeshev://yt?v=$videoId&k=${kind.code}" else url

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("k", kind.code).put("t", title).put("a", artist).put("u", url)
        .put("d", durationSec).put("f", feedUrl).put("dt", date)

    fun toMediaItem(): MediaItem {
        val uri = playUri()
        val extras = Bundle().apply { putString(EXTRA_TRACK, toJson().toString()) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    companion object {
        const val EXTRA_TRACK = "qeshev.track"

        fun fromJson(o: JSONObject) = Track(
            id = o.optString("id"),
            kind = Kind.of(o.optString("k")),
            title = o.optString("t"),
            artist = o.optString("a"),
            url = o.optString("u"),
            durationSec = o.optLong("d"),
            feedUrl = o.optString("f"),
            date = o.optLong("dt"),
        )

        fun podcast(audioUrl: String, title: String, podcast: String, durationSec: Long, feedUrl: String, date: Long) =
            Track("p:$audioUrl", Kind.PODCAST, title, podcast, audioUrl, durationSec, feedUrl, date)

        fun youtube(videoId: String, kind: Kind, title: String, artist: String, durationSec: Long) =
            Track("y:$videoId", kind, title, artist, "https://www.youtube.com/watch?v=$videoId", durationSec.coerceAtLeast(0))

        fun of(item: MediaItem?): Track? {
            val json = item?.mediaMetadata?.extras?.getString(EXTRA_TRACK) ?: return null
            return runCatching { fromJson(JSONObject(json)) }.getOrNull()
        }

        fun listToJson(list: List<Track>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }

        fun listFromJson(a: JSONArray?): List<Track> {
            if (a == null) return emptyList()
            val out = ArrayList<Track>(a.length())
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += fromJson(it) }
            return out
        }
    }
}

data class PodcastRef(val title: String, val author: String, val feedUrl: String)

data class Folder(val id: String, val name: String, val tracks: List<Track>, val created: Long)

data class Download(val track: Track, val uri: String, val mime: String, val time: Long)

data class HistoryEntry(val track: Track, val time: Long)

/** What YouTube suggested next to something that was played; the raw material for recommendations. */
data class RelatedSet(val seedId: String, val seedTitle: String, val seedKind: Kind, val items: List<Track>, val time: Long)
