package app.jubal.keshev

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Saves episodes as real audio files in the phone's Podcasts/Keshev folder,
 * where any file manager or music player can find them.
 */
class DownloadService : Service() {
    private val queue = ArrayDeque<PlayRef>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    private lateinit var notifications: NotificationManager
    @Volatile private var lastUpdate = 0L
    private var doneId = 2000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "הורדות", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            progressNotification("מתכונן להורדה…", "", -1),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (intent != null) {
            val id = intent.getStringExtra(EXTRA_ID)
            if (!id.isNullOrEmpty() && queue.none { it.mediaId == id }) {
                queue.addLast(
                    PlayRef(
                        id,
                        intent.getStringExtra(EXTRA_TITLE) ?: "",
                        intent.getStringExtra(EXTRA_ARTIST) ?: "",
                        intent.getStringExtra(EXTRA_KIND) ?: "url",
                        intent.getStringExtra(EXTRA_REF) ?: "",
                    )
                )
            }
        }
        if (!running) runQueue()
        return START_NOT_STICKY
    }

    private fun runQueue() {
        running = true
        scope.launch {
            while (queue.isNotEmpty()) {
                val job = queue.removeFirst()
                val ok = try {
                    withContext(Dispatchers.IO) { download(job) }
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Download failed", e)
                    false
                }
                notifyFinished(job, ok)
            }
            running = false
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun download(job: PlayRef) {
        val youtube = job.kind == "yt"
        var url = job.ref
        var mime: String
        var extension: String
        var total = -1L
        if (youtube) {
            val audio = YouTube.resolveAudio(job.ref)
            url = audio.url
            mime = audio.mime
            extension = audio.extension
            total = audio.contentLength.takeIf { it > 0 }
                ?: (Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() ?: -1L)
        } else {
            extension = guessExtension(url)
            mime = MIME_BY_EXTENSION[extension] ?: "audio/mpeg"
        }

        val name = safeFileName("${job.artist} - ${job.title}").take(120) + "." + extension
        val (uri, out) = openOutput(name, mime)
        try {
            out.use { o ->
                if (youtube) downloadYouTube(url, total, o, job) else downloadPlain(url, o, job)
            }
            finishOutput(uri)
        } catch (e: Exception) {
            deleteOutput(uri)
            throw e
        }
        Store.addSavedFile(
            SavedFile(job.mediaId, job.title, job.artist, uri.toString(), mime, System.currentTimeMillis())
        )
    }

    private fun downloadPlain(url: String, out: OutputStream, job: PlayRef) {
        Net.get(url).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            copy(body.byteStream(), out, body.contentLength(), 0, job)
        }
    }

    private fun downloadYouTube(url: String, total: Long, out: OutputStream, job: PlayRef) {
        var position = 0L
        var requestNumber = 1
        while (total < 0 || position < total) {
            val last = if (total < 0) position + YT_CHUNK - 1 else minOf(total, position + YT_CHUNK) - 1
            val wanted = last - position + 1
            val got = Net.youTubeChunk(url, position, last, requestNumber++).use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                copy(body.byteStream(), out, total, position, job)
            }
            if (got <= 0) {
                if (total < 0) break else throw IOException("YouTube sent no data")
            }
            position += got
            if (total < 0 && got < wanted) break
        }
    }

    private fun copy(input: InputStream, out: OutputStream, total: Long, already: Long, job: PlayRef): Long {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            showProgress(job, already + done, total)
        }
        return done
    }

    private fun showProgress(job: PlayRef, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 700) return
        lastUpdate = now
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        val sizeText = "%.1f MB".format(Locale.US, done / 1_048_576.0)
        notifications.notify(NOTIFICATION_ID, progressNotification(job.title, sizeText, percent))
    }

    private fun progressNotification(title: String, text: String, percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun notifyFinished(job: PlayRef, ok: Boolean) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "נשמר בטלפון" else "ההורדה נכשלה")
            .setContentText(job.title)
            .setAutoCancel(true)
            .build()
        notifications.notify(doneId++, n)
    }

    private fun openOutput(name: String, mime: String): Pair<Uri, OutputStream> {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PODCASTS + "/Keshev")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Audio.AudioColumns.IS_PODCAST, 1)
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create file")
            val out = contentResolver.openOutputStream(uri) ?: throw IOException("Could not open file")
            return uri to out
        }
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "Keshev")
        dir.mkdirs()
        val file = File(dir, name)
        return Uri.fromFile(file) to FileOutputStream(file)
    }

    private fun finishOutput(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
        }
    }

    private fun deleteOutput(uri: Uri) {
        try {
            if (uri.scheme == "content") contentResolver.delete(uri, null, null)
            else uri.path?.let { File(it).delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete partial file", e)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeshevDownload"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val YT_CHUNK = 2L shl 20
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_REF = "ref"

        private val MIME_BY_EXTENSION = mapOf(
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/ogg",
            "wav" to "audio/wav",
        )

        fun start(context: Context, ref: PlayRef) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_ID, ref.mediaId)
                .putExtra(EXTRA_TITLE, ref.title)
                .putExtra(EXTRA_ARTIST, ref.artist)
                .putExtra(EXTRA_KIND, ref.kind)
                .putExtra(EXTRA_REF, ref.ref)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun guessExtension(url: String): String {
            val last = Uri.parse(url).lastPathSegment ?: return "mp3"
            val ext = last.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return if (ext in MIME_BY_EXTENSION) ext else "mp3"
        }

        private fun safeFileName(text: String): String =
            text.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifEmpty { "episode" }
    }
}
