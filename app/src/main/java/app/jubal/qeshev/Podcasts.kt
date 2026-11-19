package app.jubal.qeshev

import android.util.Xml
import androidx.core.text.HtmlCompat
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class PodcastResult(
    val name: String,
    val author: String,
    val feedUrl: String,
    val episodeCount: Int,
)

data class Episode(
    val title: String,
    val date: Long,
    val durationSec: Long,
    val audioUrl: String,
    val mime: String,
    val rawDescription: String,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")

    fun toTrack(podcast: String, feedUrl: String): Track =
        Track.podcast(audioUrl, title, podcast, durationSec, feedUrl, date)
}

data class Feed(
    val title: String,
    val author: String,
    val rawDescription: String,
    val episodes: List<Episode>,
)

/** Podcast directory search (Apple's free public directory) and RSS feed reading. Text only. */
object Podcasts {

    fun search(term: String): List<PodcastResult> {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("itunes.apple.com")
            .addPathSegment("search")
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("limit", "50")
            .addQueryParameter("country", "IL")
            .addQueryParameter("term", term)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "{}")
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = ArrayList<PodcastResult>()
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                val feed = o.optString("feedUrl")
                if (feed.isBlank()) continue
                val name = o.optString("collectionName").ifBlank { o.optString("trackName") }
                out += PodcastResult(name, o.optString("artistName"), feed, o.optInt("trackCount"))
            }
            return out
        }
    }

    fun loadFeed(url: String): Feed {
        Net.get(url).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty")
            return parse(body.byteStream())
        }
    }

    private fun parse(input: InputStream): Feed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var title = ""
        var author = ""
        var description = ""
        val episodes = ArrayList<Episode>()

        var inItem = false
        var iTitle = ""
        var iDate = ""
        var iDuration = ""
        var iUrl = ""
        var iType = ""
        var iDescription = ""
        var iSummary = ""
        var iContent = ""

        val path = ArrayList<String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = p.name.lowercase(Locale.ROOT)
                val parent = path.lastOrNull()
                if (name == "item" || name == "entry") {
                    inItem = true
                    iTitle = ""; iDate = ""; iDuration = ""; iUrl = ""; iType = ""
                    iDescription = ""; iSummary = ""; iContent = ""
                    path.add(name)
                } else if (inItem && (parent == "item" || parent == "entry")) {
                    when (name) {
                        "title" -> iTitle = readText(p)
                        "pubdate", "published", "dc:date" -> iDate = readText(p)
                        "itunes:duration" -> iDuration = readText(p)
                        "description" -> iDescription = readText(p)
                        "itunes:summary" -> iSummary = readText(p)
                        "content:encoded" -> iContent = readText(p)
                        "enclosure" -> {
                            iUrl = p.getAttributeValue(null, "url") ?: ""
                            iType = p.getAttributeValue(null, "type") ?: ""
                            path.add(name)
                        }
                        "link" -> {
                            if (p.getAttributeValue(null, "rel") == "enclosure" && iUrl.isEmpty()) {
                                iUrl = p.getAttributeValue(null, "href") ?: ""
                                iType = p.getAttributeValue(null, "type") ?: ""
                            }
                            path.add(name)
                        }
                        "media:content" -> {
                            if (iUrl.isEmpty()) {
                                iUrl = p.getAttributeValue(null, "url") ?: ""
                                iType = p.getAttributeValue(null, "type") ?: ""
                            }
                            path.add(name)
                        }
                        else -> path.add(name)
                    }
                } else if (!inItem && (parent == "channel" || parent == "feed")) {
                    when (name) {
                        "title" -> title = readText(p)
                        "itunes:author", "author" -> author = readText(p)
                        "description", "subtitle" -> description = readText(p)
                        else -> path.add(name)
                    }
                } else {
                    path.add(name)
                }
            } else if (event == XmlPullParser.END_TAG) {
                val name = p.name.lowercase(Locale.ROOT)
                if (path.isNotEmpty()) path.removeAt(path.size - 1)
                if ((name == "item" || name == "entry") && inItem) {
                    inItem = false
                    if (iUrl.isNotBlank()) {
                        episodes += Episode(
                            title = iTitle.ifBlank { "פרק ללא שם" },
                            date = parseDate(iDate),
                            durationSec = parseDuration(iDuration),
                            audioUrl = iUrl.trim(),
                            mime = iType.lowercase(Locale.ROOT),
                            rawDescription = iContent.ifBlank { iDescription.ifBlank { iSummary } },
                        )
                    }
                }
            }
            event = p.next()
        }
        return Feed(title.ifBlank { "פודקאסט" }, author, description, episodes)
    }

    /** Reads all the text inside the current element, skipping any nested tags. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    /** Turns HTML descriptions into plain text: no links, no pictures. */
    fun plainText(html: String): String {
        if (html.isBlank()) return ""
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace("￼", "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun parseDuration(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        return try {
            t.split(":").fold(0L) { acc, part -> acc * 60 + part.trim().toDouble().toLong() }
        } catch (e: Exception) {
            0
        }
    }

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd",
    )

    fun parseDate(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        for (format in DATE_FORMATS) {
            try {
                val parsed = SimpleDateFormat(format, Locale.US).parse(t)
                if (parsed != null) return parsed.time
            } catch (e: Exception) {
                // try the next format
            }
        }
        return 0
    }
}
