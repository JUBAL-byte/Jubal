package com.jubal.jubal_lite

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one piece of this app that is not Dart.
 *
 * Reaching YouTube's audio means running YouTube's own player JavaScript to
 * undo its signature scrambling, and that is a solved problem exactly once:
 * in NewPipe's extractor, which is maintained against every change YouTube
 * makes. Reimplementing it in Dart is how the previous app ended up stuck at
 * 0:00, so here the extractor is used as it stands and Dart talks to it over
 * a method channel.
 *
 * Two calls, nothing else: find songs, and turn one into a playable address.
 */
object YouTubeBridge {
    private const val CHANNEL = "jubal/youtube"

    /** Extraction is network-bound and must never touch the UI thread. */
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var initialised = false

    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "search" -> background(result) {
                    search(call.argument<String>("query").orEmpty())
                }
                "stream" -> background(result) {
                    stream(call.argument<String>("id").orEmpty())
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun background(result: MethodChannel.Result, work: () -> Any?) {
        worker.execute {
            try {
                ensureInitialised()
                val value = work()
                main.post { result.success(value) }
            } catch (error: Throwable) {
                // The message carries the exception's own type and text, so a
                // failure names itself instead of arriving as a bare null.
                val detail = "${error.javaClass.simpleName}: ${error.message ?: "no detail"}"
                main.post { result.error("youtube", detail, null) }
            }
        }
    }

    private fun ensureInitialised() {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            NewPipe.init(HttpDownloader(), Localization("en", "US"))
            initialised = true
        }
    }

    private fun search(query: String): List<Map<String, Any?>> {
        if (query.isBlank()) return emptyList()

        val handler = ServiceList.YouTube.searchQHFactory.fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            "",
        )
        val extractor = ServiceList.YouTube.getSearchExtractor(handler)
        extractor.fetchPage()

        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .take(40)
            .map { item ->
                mapOf(
                    "id" to videoId(item.url),
                    "title" to item.name,
                    "artist" to item.uploaderName,
                    "duration" to item.duration,
                )
            }
    }

    private fun stream(id: String): Map<String, Any?> {
        val info = StreamInfo.getInfo(
            ServiceList.YouTube,
            "https://www.youtube.com/watch?v=$id",
        )

        val best = info.audioStreams
            .filter { !it.content.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate }
            ?: throw IllegalStateException("No audio stream was offered for $id")

        return mapOf(
            "url" to best.content,
            "title" to info.name,
            "artist" to info.uploaderName,
            "duration" to info.duration,
            "bitrate" to best.averageBitrate,
        )
    }

    private fun videoId(url: String): String =
        Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast('/')
}

/**
 * The extractor asks for its own HTTP client so that callers control timeouts
 * and headers. YouTube answers differently depending on who it thinks is
 * asking, so the user agent here is deliberate rather than incidental.
 */
private class HttpDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val data = request.dataToSend()
        val body = data?.toRequestBody(null, 0, data.size)

        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (value in values) {
                builder.addHeader(name, value)
            }
        }

        val response = client.newCall(builder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("YouTube asked for a captcha", request.url())
        }

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            response.body?.string(),
            response.request.url.toString(),
        )
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"
    }
}
