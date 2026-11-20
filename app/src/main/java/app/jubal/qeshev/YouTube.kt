package app.jubal.qeshev

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class YtAudio(val url: String, val mime: String, val extension: String, val contentLength: Long)

sealed interface YtResult
class YtTrackResult(val track: Track, val views: Long = -1) : YtResult
class YtListResult(val list: YtList) : YtResult
class YtArtistResult(val artist: ArtistRef) : YtResult

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
        const val MUSIC_VIDEOS = YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
        const val ALBUMS = YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
        const val MUSIC_PLAYLISTS = YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
        const val ARTISTS = YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
    }
}

/** An artist's page: singles (up to three songs) and albums, newest first, the way YouTube lists "Releases". */
data class ArtistPage(
    val name: String,
    val url: String,
    val subscribers: Long,
    val singles: List<YtList>,
    val albums: List<YtList>,
)

/** Details shown under "פרטים". */
data class Details(val title: String, val uploader: String, val description: String, val views: Long, val date: String)

object YouTube {
    private class Cached(val audio: YtAudio, val time: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val details = ConcurrentHashMap<String, Details>()
    private val lists = ConcurrentHashMap<String, Pair<Long, Pair<String, List<Track>>>>()
    private val artists = ConcurrentHashMap<String, Pair<Long, ArtistPage>>()
    private const val CACHE_MS = 3 * 60 * 60 * 1000L
    private const val PAGE_CACHE_MS = 6 * 60 * 60 * 1000L

    fun videoId(url: String): String? = try {
        YoutubeStreamLinkHandlerFactory.getInstance().getId(url)
    } catch (e: Exception) {
        val uri = Uri.parse(url)
        uri.getQueryParameter("v") ?: if ((uri.host ?: "").contains("youtu.be")) uri.lastPathSegment else null
    }

    fun channelId(url: String): String? = Regex("/channel/(UC[\\w-]+)").find(url)?.groupValues?.get(1)

    /** The same channel, as a www.youtube.com address the extractor understands. */
    fun channelUrl(url: String): String =
        channelId(url)?.let { "https://www.youtube.com/channel/$it" } ?: url.replace("music.youtube.com", "www.youtube.com")

    fun artistKey(url: String, name: String): String =
        channelId(url)?.let { "c:$it" } ?: "n:${Podcasts.norm(name)}"

    fun convert(items: List<InfoItem>, kind: Kind): List<YtResult> = items.mapNotNull { item ->
        when (item) {
            is StreamInfoItem -> {
                if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) {
                    null
                } else {
                    val id = videoId(item.url)
                    if (id.isNullOrEmpty()) {
                        null
                    } else {
                        YtTrackResult(
                            Track.youtube(id, kind, item.name ?: "", cleanName(item.uploaderName), item.duration, item.uploaderUrl ?: ""),
                            item.viewCount,
                        )
                    }
                }
            }
            is PlaylistInfoItem -> YtListResult(
                YtList(item.name ?: "", cleanName(item.uploaderName), item.url, item.streamCount, kind, item.uploaderUrl ?: "")
            )
            is ChannelInfoItem -> YtArtistResult(ArtistRef(cleanName(item.name), item.url ?: "", item.subscriberCount))
            else -> null
        }
    }

    /** YouTube's automatic artist channels are called "Name - Topic"; show just the name. */
    private fun cleanName(name: String?): String = (name ?: "").removeSuffix(" - Topic").trim()

    private fun tracks(items: List<InfoItem>, kind: Kind): List<Track> =
        convert(items, kind).mapNotNull { (it as? YtTrackResult)?.track }

    /** Loads a playlist or album, up to a few hundred items. */
    fun loadList(url: String, kind: Kind): Pair<String, List<Track>> {
        lists[url]?.let { if (System.currentTimeMillis() - it.first < PAGE_CACHE_MS) return it.second }
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
        val result = (info.name ?: "") to out.distinctBy { it.id }
        lists[url] = System.currentTimeMillis() to result
        return result
    }

    /** Finds an artist's channel by name (used when only the name is known). */
    fun findArtist(name: String): ArtistRef? =
        YtSearch(name, YtSearch.ARTISTS, Kind.MUSIC).first().firstNotNullOfOrNull { (it as? YtArtistResult)?.artist }

    /** Albums and singles from the artist's "Releases", newest first. */
    fun artistPage(url: String, fallbackName: String): ArtistPage {
        val channel = channelUrl(url)
        artists[channel]?.let { if (System.currentTimeMillis() - it.first < PAGE_CACHE_MS) return it.second }
        val service = ServiceList.YouTube
        val info = ChannelInfo.getInfo(service, channel)
        val name = info.name?.removeSuffix(" - Topic")?.ifBlank { null } ?: fallbackName
        val releases = ArrayList<YtList>()
        val tab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.ALBUMS) }
        if (tab != null) {
            runCatching {
                ChannelTabInfo.getInfo(service, tab).relatedItems.forEach { item ->
                    if (item is PlaylistInfoItem) {
                        releases += YtList(item.name ?: "", name, item.url, item.streamCount, Kind.MUSIC, channel)
                    }
                }
            }
        }
        if (releases.isEmpty()) {
            // No "Releases" tab: fall back to an album search and keep only this artist's albums
            runCatching {
                val id = channelId(channel)
                YtSearch(name, YtSearch.ALBUMS, Kind.MUSIC).first().forEach { r ->
                    val l = (r as? YtListResult)?.list ?: return@forEach
                    val same = (id != null && channelId(l.artistUrl) == id) || Podcasts.norm(l.artist).contains(Podcasts.norm(name))
                    if (same) releases += l.copy(artist = name, artistUrl = channel)
                }
            }
        }
        val singles = releases.filter { it.count in 1..3 }
        val albums = releases.filterNot { it.count in 1..3 }
        val page = ArtistPage(name, channel, info.subscriberCount, singles, albums)
        artists[channel] = System.currentTimeMillis() to page
        return page
    }

    /**
     * The artist's most played songs. Song results don't carry play counts, so they are matched
     * with the artist's music videos, which do, and sorted by that.
     */
    suspend fun topSongs(artist: ArtistRef, limit: Int = 10): List<Pair<Track, Long>> = withContext(Dispatchers.IO) {
        coroutineScope {
            val songsJob = async { runCatching { YtSearch(artist.name, YtSearch.SONGS, Kind.MUSIC).first() }.getOrDefault(emptyList()) }
            val videosJob = async { runCatching { YtSearch(artist.name, YtSearch.MUSIC_VIDEOS, Kind.MUSIC).first() }.getOrDefault(emptyList()) }
            val id = channelId(artist.url)
            val name = Podcasts.norm(artist.name)
            fun mine(t: Track): Boolean =
                (id != null && channelId(t.artistUrl) == id) || Podcasts.norm(t.artist).contains(name)

            val songs = songsJob.await().mapNotNull { it as? YtTrackResult }.map { it.track }.filter { mine(it) }
            val videos = videosJob.await().mapNotNull { it as? YtTrackResult }.filter { mine(it.track) }
            val views = HashMap<String, Long>()
            videos.forEach { v ->
                val key = titleKey(v.track.title, artist.name)
                if (v.views > (views[key] ?: -1)) views[key] = v.views
            }
            val ranked = songs.map { s -> s to (views[titleKey(s.title, artist.name)] ?: -1L) }
            val known = ranked.filter { it.second >= 0 }.sortedByDescending { it.second }
            val unknown = ranked.filter { it.second < 0 }
            val list = (known + unknown).distinctBy { it.first.id }
            val result = if (list.isNotEmpty()) list else videos.map { it.track to it.views }.sortedByDescending { it.second }
            result.take(limit).map { (t, v) -> t.copy(artist = artist.name, artistUrl = channelUrl(artist.url.ifBlank { t.artistUrl })) to v }
        }
    }

    private fun titleKey(title: String, artist: String): String {
        var t = Podcasts.norm(title)
        val a = Podcasts.norm(artist)
        if (a.isNotEmpty()) t = t.replace(a, " ")
        t = t.replace(Regex("\\b(official|video|audio|lyrics?|clip|hd|4k|קליפ|רשמי|מילים)\\b"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Loads the tracks of several albums, a few at a time. */
    suspend fun loadAlbums(lists: List<YtList>): List<Track> = withContext(Dispatchers.IO) {
        val gate = Semaphore(3)
        coroutineScope {
            lists.map { l ->
                async { gate.withPermit { runCatching { loadList(l.url, l.kind).second }.getOrDefault(emptyList()) } }
            }.awaitAll().flatten().distinctBy { it.id }
        }
    }

    /** Title, artist and length for a link opened from outside the app. */
    fun trackFor(videoId: String, kind: Kind): Track {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        remember(videoId, kind, info)
        return Track.youtube(videoId, kind, info.name ?: "", cleanName(info.uploaderName), info.duration, info.uploaderUrl ?: "")
    }

    fun details(track: Track): Details {
        details[track.videoId]?.let { return it }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${track.videoId}")
        remember(track.videoId, track.kind, info)
        return details[track.videoId] ?: Details(track.title, track.artist, "", -1, "")
    }

    private fun describe(d: Description?): String {
        val content = d?.content ?: return ""
        return if (d.type == Description.Type.HTML) Podcasts.plainText(content) else content.trim()
    }

    /**
     * Finds the audio-only stream of a video (video streams are never requested)
     * and keeps what YouTube suggests next to it, for recommendations and autoplay.
     */
    fun audioFor(videoId: String, kind: Kind): YtAudio {
        cache[videoId]?.let { if (System.currentTimeMillis() - it.time < CACHE_MS) return it.audio }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        return remember(videoId, kind, info) ?: throw IOException("No audio stream")
    }

    private fun remember(videoId: String, kind: Kind, info: StreamInfo): YtAudio? {
        runCatching {
            details[videoId] = Details(
                info.name ?: "", info.uploaderName ?: "", describe(info.description), info.viewCount,
                info.textualUploadDate ?: "",
            )
        }
        runCatching {
            val related = tracks(info.relatedItems, kind)
                .filter { it.videoId != videoId && it.durationSec in 1..(if (kind == Kind.MUSIC) 900L else 4 * 3600L) }
            Store.setRelated(RelatedSet("y:$videoId", info.name ?: "", kind, related, System.currentTimeMillis()))
        }
        val streams = info.audioStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl
        }
        if (streams.isEmpty()) return null
        val best = streams.maxBy { score(it) }
        val format = best.format
        val audio = YtAudio(
            url = best.content,
            mime = format?.mimeType ?: "audio/mp4",
            extension = if (format == MediaFormat.M4A) "m4a" else (format?.suffix ?: "m4a"),
            contentLength = best.itagItem?.contentLength ?: -1L,
        )
        cache[videoId] = Cached(audio, System.currentTimeMillis())
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
