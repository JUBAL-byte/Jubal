package app.jubal.keshev

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

data class YtVideo(val url: String, val title: String, val channel: String, val durationSec: Long)

data class YtAudio(
    val url: String,
    val mime: String,
    val extension: String,
    val contentLength: Long,
)

/** One YouTube search, with "more results" paging. Only titles are read, never thumbnails. */
class YtSearch(query: String) {
    private val service = ServiceList.YouTube
    private val handler = service.getSearchQHFactory()
        .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
    private var next: Page? = null

    var hasMore = false
        private set

    fun first(): List<YtVideo> {
        val info = SearchInfo.getInfo(service, handler)
        next = info.nextPage
        hasMore = info.hasNextPage()
        return toVideos(info.relatedItems)
    }

    fun more(): List<YtVideo> {
        val page = next ?: return emptyList()
        val result = SearchInfo.getMoreItems(service, handler, page)
        next = result.nextPage
        hasMore = result.hasNextPage()
        return toVideos(result.items)
    }

    private fun toVideos(items: List<InfoItem>): List<YtVideo> =
        items.filterIsInstance<StreamInfoItem>()
            .filter {
                it.streamType != StreamType.LIVE_STREAM &&
                    it.streamType != StreamType.AUDIO_LIVE_STREAM
            }
            .map { YtVideo(it.url, it.name ?: "", it.uploaderName ?: "", it.duration) }
}

object YouTube {
    /** Finds the audio-only stream of a video. Video streams are never requested. */
    fun resolveAudio(videoUrl: String): YtAudio {
        val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val streams = info.audioStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl
        }
        if (streams.isEmpty()) throw IOException("No audio stream")
        val best = streams.maxBy { score(it) }
        val format = best.format
        return YtAudio(
            url = best.content,
            mime = format?.mimeType ?: "audio/mp4",
            extension = if (format == MediaFormat.M4A) "m4a" else (format?.suffix ?: "m4a"),
            contentLength = best.itagItem?.contentLength ?: -1L,
        )
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
