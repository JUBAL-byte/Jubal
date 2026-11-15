package app.jubal.keshev

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.TimeUnit

/** Shared HTTP client, plus the special way YouTube media files have to be requested. */
object Net {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** YouTube's own "Restricted Mode" — filters out content YouTube marks as mature. */
    const val YOUTUBE_RESTRICTED_COOKIE = "PREF=f2=8000000"

    private val POST_BODY = byteArrayOf(0x78, 0)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun get(url: String): okhttp3.Response =
        client.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        ).execute()

    fun isYouTubeMedia(url: String): Boolean {
        val uri = Uri.parse(url)
        return (uri.host ?: "").endsWith("googlevideo.com") &&
            (uri.path ?: "").startsWith("/videoplayback")
    }

    /**
     * Asks YouTube for one slice of a media file, the same way NewPipe does
     * (POST, range in the URL, a running request number). Big open-ended
     * requests get cut off or throttled, small slices do not.
     */
    fun youTubeChunk(url: String, start: Long, endInclusive: Long, requestNumber: Int): okhttp3.Response {
        val full = "$url&rn=$requestNumber&range=$start-$endInclusive"
        val agent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            USER_AGENT
        }
        val builder = Request.Builder()
            .url(full)
            .post(POST_BODY.toRequestBody(null))
            .header("User-Agent", agent)
            .header("Accept-Encoding", "identity")
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        return client.newCall(builder.build()).execute()
    }
}
