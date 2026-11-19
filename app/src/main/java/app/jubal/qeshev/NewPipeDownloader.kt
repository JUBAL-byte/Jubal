package app.jubal.qeshev

import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** Lets the YouTube extractor make its web requests through our HTTP client. */
class NewPipeDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(method, body)
            .header("User-Agent", Net.USER_AGENT)

        var cookie: String? = null
        for ((name, values) in request.headers()) {
            if (name.equals("Cookie", ignoreCase = true)) {
                cookie = values.joinToString("; ")
                continue
            }
            builder.removeHeader(name)
            for (value in values) builder.addHeader(name, value)
        }
        if (url.contains("youtube.com")) {
            cookie = if (cookie.isNullOrEmpty()) {
                Net.YOUTUBE_RESTRICTED_COOKIE
            } else {
                "$cookie; ${Net.YOUTUBE_RESTRICTED_COOKIE}"
            }
        }
        if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)

        Net.client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val text = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                text,
                response.request.url.toString(),
            )
        }
    }
}
