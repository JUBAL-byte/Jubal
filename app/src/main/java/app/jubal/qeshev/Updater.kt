package app.jubal.qeshev

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * Keeps YouTube audio working after YouTube changes things.
 *
 * Every day a job on GitHub checks whether NewPipe has shipped a newer YouTube
 * extractor; if so it builds and publishes a new JUBAL with it. This object finds
 * that newer build and installs it over the current one with one tap (same key,
 * so nothing saved is lost). It talks only to the GitHub API and never opens a browser.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/JUBAL-byte/Jubal/releases?per_page=30"
    private const val CHECK_EVERY_MS = 6 * 3_600_000L
    private val TAG = Regex("^jubal-app-(\\d+)$")

    data class Release(val number: Long, val apkUrl: String, val size: Long)

    fun installed(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(0L)

    /** The newest published JUBAL build, or null if GitHub can't be reached. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "JUBAL")
            .build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val releases = JSONArray(response.body?.string() ?: return@withContext null)
            var best: Release? = null
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val number = TAG.find(r.optString("tag_name"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
                if (best != null && number <= best.number) continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk")) {
                        best = Release(number, a.optString("browser_download_url"), a.optLong("size"))
                        break
                    }
                }
            }
            best
        }
    }

    /** A build newer than the one installed, or null. Without [force], asks GitHub at most every few hours. */
    suspend fun newer(context: Context, force: Boolean): Release? {
        val now = System.currentTimeMillis()
        if (!force && now - Store.lastUpdateCheck() < CHECK_EVERY_MS) return null
        val release = runCatching { latest() }.getOrNull() ?: return null
        Store.setLastUpdateCheck(now)
        return release.takeIf { it.number > installed(context) && it.apkUrl.isNotBlank() }
    }

    /** Downloads the APK into the app's own cache. [progress] gets 0..100 on the main thread. */
    suspend fun download(context: Context, release: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "JUBAL-${release.number}.apk")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "JUBAL").build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != shown) {
                            shown = percent
                            withContext(Dispatchers.Main) { progress(percent) }
                        }
                    }
                }
            }
        }
        file
    }

    /**
     * Hands the APK to Android's installer. The first time, Android itself asks to
     * allow updates from JUBAL, and then continues with the install.
     */
    fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}
