package app.jubal.qeshev

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

enum class Kind(val code: String, val label: String) {
    PODCAST("p", "פרק"),
    VIDEO("v", "יוטיוב"),
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
    /** YouTube channel of the artist (music and videos). */
    val artistUrl: String = "",
    /** Who makes the podcast (episodes only). */
    val author: String = "",
    /** The episode's own web page, when the feed gives one. */
    val page: String = "",
    /** The link this came from (for example a Spotify link), kept for sharing. */
    val link: String = "",
) {
    val isYouTube: Boolean get() = kind != Kind.PODCAST
    val videoId: String get() = if (isYouTube) id.removePrefix("y:") else ""

    /** The address handed to the player. YouTube items are turned into real audio links only when played. */
    fun playUri(): String = if (isYouTube) "qeshev://yt?v=$videoId&k=${kind.code}" else url

    /** The original link, for sharing or copying. */
    fun shareLink(): String = when {
        link.isNotBlank() -> link
        kind == Kind.MUSIC -> "https://music.youtube.com/watch?v=$videoId"
        kind == Kind.VIDEO -> "https://www.youtube.com/watch?v=$videoId"
        page.isNotBlank() -> page
        else -> Store.podcastMeta(feedUrl)?.appleUrl()?.takeIf { it.isNotBlank() } ?: url
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("k", kind.code).put("t", title).put("a", artist).put("u", url)
        .put("d", durationSec).put("f", feedUrl).put("dt", date)
        .put("au", artistUrl).put("by", author).put("pg", page).put("ln", link)

    /** [active] marks something the listener picked themselves, which is what recommendations learn from. */
    fun toMediaItem(active: Boolean = false, context: PlayContext? = null): MediaItem {
        val uri = playUri()
        val extras = Bundle().apply {
            putString(EXTRA_TRACK, toJson().toString())
            putBoolean(EXTRA_ACTIVE, active)
            if (context != null) putString(EXTRA_CONTEXT, context.toJson().toString())
        }
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
        const val EXTRA_ACTIVE = "qeshev.active"
        const val EXTRA_CONTEXT = "qeshev.context"

        fun fromJson(o: JSONObject) = Track(
            id = o.optString("id"),
            kind = Kind.of(o.optString("k")),
            title = o.optString("t"),
            artist = o.optString("a"),
            url = o.optString("u"),
            durationSec = o.optLong("d"),
            feedUrl = o.optString("f"),
            date = o.optLong("dt"),
            artistUrl = o.optString("au"),
            author = o.optString("by"),
            page = o.optString("pg"),
            link = o.optString("ln"),
        )

        fun podcast(
            audioUrl: String, title: String, podcast: String, durationSec: Long, feedUrl: String, date: Long,
            author: String = "", page: String = "",
        ) = Track("p:$audioUrl", Kind.PODCAST, title, podcast, audioUrl, durationSec, feedUrl, date, author = author, page = page)

        fun youtube(videoId: String, kind: Kind, title: String, artist: String, durationSec: Long, artistUrl: String = "") =
            Track(
                "y:$videoId", kind, title, artist, "https://www.youtube.com/watch?v=$videoId",
                durationSec.coerceAtLeast(0), artistUrl = artistUrl,
            )

        fun of(item: MediaItem?): Track? {
            val json = item?.mediaMetadata?.extras?.getString(EXTRA_TRACK) ?: return null
            return runCatching { fromJson(JSONObject(json)) }.getOrNull()
        }

        fun isActive(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(EXTRA_ACTIVE, false) ?: false

        fun listToJson(list: List<Track>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }

        fun listFromJson(a: JSONArray?): List<Track> {
            if (a == null) return emptyList()
            val out = ArrayList<Track>(a.length())
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += fromJson(it) }
            return out
        }
    }
}

/** Where a playback was started from: an album, playlist, artist, podcast or a single song. */
data class PlayContext(
    val type: String,
    val title: String,
    val subtitle: String,
    val ref: String,
    val kind: Kind,
    val track: Track? = null,
    val artistUrl: String = "",
) {
    /** Same album / playlist / artist / song counts as one entry in "recently played". */
    val key: String get() = "$type|${if (type == SONG) track?.id ?: ref else ref}"

    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("title", title).put("sub", subtitle).put("ref", ref).put("k", kind.code)
        .put("au", artistUrl)
        .also { o -> track?.let { o.put("track", it.toJson()) } }

    companion object {
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val YT_LIST = "ytlist"
        const val ARTIST = "artist"
        const val LIKED = "liked"
        const val PODCAST = "podcast"
        const val SONG = "song"

        fun fromJson(o: JSONObject) = PlayContext(
            o.optString("type"), o.optString("title"), o.optString("sub"), o.optString("ref"), Kind.of(o.optString("k")),
            o.optJSONObject("track")?.let { Track.fromJson(it) }, o.optString("au"),
        )

        fun song(t: Track) = PlayContext(SONG, t.title, t.artist, t.id, t.kind, t, t.artistUrl)
        fun podcast(t: Track) = PlayContext(PODCAST, t.artist, t.author, t.feedUrl, Kind.PODCAST)
        fun liked() = PlayContext(LIKED, "אהבתי", "השירים שאהבת", "", Kind.MUSIC)
    }
}

data class ArtistRef(val name: String, val url: String, val subscribers: Long = -1) {
    val key: String get() = YouTube.artistKey(url, name)

    fun toJson(): JSONObject = JSONObject().put("name", name).put("url", url).put("subs", subscribers)

    companion object {
        fun fromJson(o: JSONObject) = ArtistRef(o.optString("name"), o.optString("url"), o.optLong("subs", -1))
    }
}

data class PodcastRef(
    val title: String,
    val author: String,
    val feedUrl: String,
    val collectionId: Long = 0,
    val genreIds: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
) {
    fun appleUrl(): String = if (collectionId > 0) "https://podcasts.apple.com/podcast/id$collectionId" else ""

    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("author", author).put("feedUrl", feedUrl).put("cid", collectionId)
        .put("gids", JSONArray(genreIds)).put("genres", JSONArray(genres))

    companion object {
        fun fromJson(o: JSONObject): PodcastRef {
            fun strings(a: JSONArray?): List<String> =
                if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            return PodcastRef(
                o.optString("title"), o.optString("author"), o.optString("feedUrl"), o.optLong("cid"),
                strings(o.optJSONArray("gids")), strings(o.optJSONArray("genres")),
            )
        }
    }
}

/** A YouTube Music album, single or playlist. */
data class YtList(
    val title: String,
    val artist: String,
    val url: String,
    val count: Long,
    val kind: Kind,
    val artistUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("artist", artist).put("url", url).put("count", count).put("k", kind.code)
        .put("au", artistUrl)

    fun shareLink(): String {
        val list = Uri.parse(url).getQueryParameter("list")
        return if (list != null) "https://music.youtube.com/playlist?list=$list" else url
    }

    companion object {
        fun fromJson(o: JSONObject) = YtList(
            o.optString("title"), o.optString("artist"), o.optString("url"), o.optLong("count", -1),
            Kind.of(o.optString("k")), o.optString("au"),
        )
    }
}

data class Folder(val id: String, val name: String, val tracks: List<Track>, val created: Long)

data class Download(val track: Track, val uri: String, val mime: String, val time: Long)

data class HistoryEntry(val track: Track, val time: Long, val active: Boolean)

/** What YouTube suggested next to something that was played; the raw material for recommendations. */
data class RelatedSet(val seedId: String, val seedTitle: String, val seedKind: Kind, val items: List<Track>, val time: Long)

/** How a listener treated one item. Only items they chose themselves teach the recommendations. */
data class Stat(
    val track: Track,
    val active: Int = 0,
    val passive: Int = 0,
    val completes: Int = 0,
    val skips: Int = 0,
    val listenedMs: Long = 0,
    val last: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", track.toJson()).put("a", active).put("p", passive).put("c", completes)
        .put("s", skips).put("l", listenedMs).put("last", last)

    companion object {
        fun fromJson(o: JSONObject) = Stat(
            Track.fromJson(o.getJSONObject("t")), o.optInt("a"), o.optInt("p"), o.optInt("c"),
            o.optInt("s"), o.optLong("l"), o.optLong("last"),
        )
    }
}
