package app.jubal.qeshev

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import android.content.Context
import androidx.media3.datasource.ResolvingDataSource
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Sends YouTube media requests to [YouTubeDataSource], everything else to the normal HTTP source. */
@OptIn(UnstableApi::class)
class RoutingHttpDataSource : DataSource {
    private val normal: DataSource = DefaultHttpDataSource.Factory()
        .setUserAgent(Net.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)
        .createDataSource()
    private val youtube = YouTubeDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        youtube.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (Net.isYouTubeMedia(dataSpec.uri.toString())) youtube else normal
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingHttpDataSource()
    }
}

/** Reads a YouTube audio file in 1 MB slices, so long episodes keep playing without being cut off. */
@OptIn(UnstableApi::class)
class YouTubeDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var spec: DataSpec? = null
    private var url = ""
    private var position = 0L
    private var end = UNKNOWN
    private var response: okhttp3.Response? = null
    private var body: InputStream? = null
    private var chunkWanted = 0L
    private var chunkGot = 0L
    private var requestNumber = 1
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        url = dataSpec.uri.toString()
        transferInitializing(dataSpec)
        position = dataSpec.position
        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length
            clen != null && clen > 0 -> clen
            else -> UNKNOWN
        }
        if (end != UNKNOWN && position > end) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        if (end == UNKNOWN || position < end) openChunk()
        opened = true
        transferStarted(dataSpec)
        return if (end == UNKNOWN) C.LENGTH_UNSET.toLong() else end - position
    }

    private fun openChunk() {
        val last = if (end == UNKNOWN) position + CHUNK - 1 else minOf(end, position + CHUNK) - 1
        chunkWanted = last - position + 1
        chunkGot = 0
        val r = Net.youTubeChunk(url, position, last, requestNumber++)
        if (!r.isSuccessful) {
            val code = r.code
            r.close()
            // An expired or refused link: forget it, so the retry asks YouTube for a fresh one.
            if (code == 403 || code == 410) YouTube.invalidateUrl(url)
            throw IOException("YouTube HTTP $code")
        }
        response = r
        body = r.body?.byteStream() ?: throw IOException("Empty response")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (end != UNKNOWN && position >= end) return C.RESULT_END_OF_INPUT
            var input = body
            if (input == null) {
                openChunk()
                input = body ?: return C.RESULT_END_OF_INPUT
            }
            val n = input.read(buffer, offset, length)
            if (n > 0) {
                position += n
                chunkGot += n
                bytesTransferred(n)
                return n
            }
            val gotNothing = chunkGot == 0L
            val shortChunk = chunkGot < chunkWanted
            closeChunk()
            if (shortChunk && end == UNKNOWN) return C.RESULT_END_OF_INPUT
            if (gotNothing) throw IOException("YouTube sent no data")
        }
    }

    private fun closeChunk() {
        try {
            body?.close()
        } catch (e: IOException) {
            // ignore
        }
        response?.close()
        body = null
        response = null
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        closeChunk()
        if (opened) {
            opened = false
            transferEnded()
        }
        spec = null
    }

    companion object {
        private const val UNKNOWN = -1L
        private const val CHUNK = 1L shl 20
    }
}

/**
 * Decides where each item is actually played from, right when it is needed:
 * a file saved on the phone if there is one, otherwise a fresh YouTube audio link
 * or the podcast's own address.
 */
@OptIn(UnstableApi::class)
class StreamResolver(private val context: Context) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        val youtube = uri.scheme == "qeshev"
        val videoId = if (youtube) uri.getQueryParameter("v") ?: throw IOException("Missing video id") else ""
        val id = if (youtube) "y:$videoId" else "p:$uri"

        Store.downloadFor(id)?.let { saved ->
            if (exists(saved.uri)) return dataSpec.withUri(Uri.parse(saved.uri))
            Store.removeDownload(id)
        }
        if (!youtube) return dataSpec

        val audio = try {
            YouTube.audioFor(videoId, Kind.of(uri.getQueryParameter("k")))
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        }
        return dataSpec.withUri(Uri.parse(audio.url))
    }

    private fun exists(uriString: String): Boolean = try {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "content") {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } else {
            File(uri.path ?: "").exists()
        }
    } catch (e: Exception) {
        false
    }
}
