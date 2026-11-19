package app.jubal.qeshev

import android.net.Uri
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class YtAudio(val url: String, val mime: String, val extension: String, val contentLength: Long)

/** A YouTube playlist or album found in search. */
data class YtList(val title: String, val artist: String, val url: String, val count: Long, val kind: Kind)

sealed interface YtResult
class YtTrackResult(val track: Track) : YtResult
class YtListResult(val list: YtList) : YtResult

/** One YouTube search, with "more results" paging. Only names are read, never thumbnails. */
class YtSearch(query: String, filter: String, private val kind: Kind) {
    private val service = ServiceList.YouTube
    private val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
    private var next: Page? = null

    var hasMore = false
        private set

    fun first(): List<YtResult> {
        val info = SearchInfo.getInfo(service, handler)
        next = info.nextPage
        hasMore = info.hasNextPage()
        return YouTube.convert(info.relatedItems, kind)
    }

    fun more(): List<YtResult> {
        val page = next ?: return emptyList()
        val result = SearchInfo.getMoreItems(service, handler, page)
        next = result.nextPage
        hasMore = result.hasNextPage()
        return YouTube.convert(result.items, kind)
    }

    companion object {
        const val VIDEOS = YoutubeSearchQueryHandlerFactory.VIDEOS
        const val PLAYLISTS = YoutubeSearchQueryHandlerFactory.PLAYLISTS
        const val SONGS = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        const val ALBUMS = YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
        const val MUSIC_PLAYLISTS = YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
    }
}

object YouTube {
    private class Cached(val audio: YtAudio, val time: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 3 * 60 * 60 * 1000L

    fun videoId(url: String): String? = try {
        YoutubeStreamLinkHandlerFactory.getInstance().getId(url)
    } catch (e: Exception) {
        val uri = Uri.parse(url)
        uri.getQueryParameter("v") ?: if ((uri.host ?: "").contains("youtu.be")) uri.lastPathSegment else null
    }

    fun convert(items: List<InfoItem>, kind: Kind): List<YtResult> = items.mapNotNull { item ->
        when (item) {
            is StreamInfoItem -> {
                if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) {
                    null
                } else {
                    val id = videoId(item.url)
                    if (id.isNullOrEmpty()) null
                    else YtTrackResult(Track.youtube(id, kind, item.name ?: "", item.uploaderName ?: "", item.duration))
                }
            }
            is PlaylistInfoItem -> YtListResult(
                YtList(item.name ?: "", item.uploaderName ?: "", item.url, item.streamCount, kind)
            )
            else -> null
        }
    }

    private fun tracks(items: List<InfoItem>, kind: Kind): List<Track> =
        convert(items, kind).mapNotNull { (it as? YtTrackResult)?.track }

    /** Loads a playlist or album, up to a few hundred items. */
    fun loadList(url: String, kind: Kind): Pair<String, List<Track>> {
        val service = ServiceList.YouTube
        val info = PlaylistInfo.getInfo(service, url)
        val out = ArrayList<Track>()
        out += tracks(info.relatedItems, kind)
        var page = info.nextPage
        var hasNext = info.hasNextPage()
        var guard = 0
        while (hasNext && page != null && guard++ < 5) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            out += tracks(more.items, kind)
            page = more.nextPage
            hasNext = more.hasNextPage()
        }
        return (info.name ?: "") to out.distinctBy { it.id }
    }

    /**
     * Finds the audio-only stream of a video (video streams are never requested)
     * and keeps what YouTube suggests next to it, for recommendations and autoplay.
     */
    fun audioFor(videoId: String, kind: Kind): YtAudio {
        cache[videoId]?.let { if (System.currentTimeMillis() - it.time < CACHE_MS) return it.audio }

        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val streams = info.audioStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl
        }
        if (streams.isEmpty()) throw IOException("No audio stream")
        val best = streams.maxBy { score(it) }
        val format = best.format
        val audio = YtAudio(
            url = best.content,
            mime = format?.mimeType ?: "audio/mp4",
            extension = if (format == MediaFormat.M4A) "m4a" else (format?.suffix ?: "m4a"),
            contentLength = best.itagItem?.contentLength ?: -1L,
        )
        cache[videoId] = Cached(audio, System.currentTimeMillis())

        runCatching {
            val related = tracks(info.relatedItems, kind)
                .filter { it.videoId != videoId && it.durationSec in 1..(if (kind == Kind.MUSIC) 900L else 4 * 3600L) }
            Store.setRelated(RelatedSet("y:$videoId", info.name ?: "", kind, related, System.currentTimeMillis()))
        }
        return audio
    }

    /** Called when YouTube refuses an old link, so the next try fetches a fresh one. */
    fun invalidateUrl(url: String) {
        cache.entries.removeAll { it.value.audio.url == url }
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    private fun score(stream: AudioStream): Int {
        var score = 0
        val type = stream.audioTrackType
        if (type == null || type == AudioTrackType.ORIGINAL) score += 1000
        if (stream.format == MediaFormat.M4A) score += 100
        val bitrate = stream.averageBitrate
        if (bitrate in 1..170) score += bitrate / 10
        return score
    }
}
