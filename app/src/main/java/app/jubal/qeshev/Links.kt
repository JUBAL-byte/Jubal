package app.jubal.qeshev

import android.net.Uri
import androidx.core.text.HtmlCompat
import java.util.Locale

/**
 * Opens links from YouTube, YouTube Music, Spotify and Apple Podcasts inside the app, audio only.
 * Spotify doesn't let other apps play its audio, so its links are matched by name:
 * songs, albums and artists on YouTube Music, episodes and shows in the podcast directory.
 */
object Links {
    sealed interface Target
    class PlayTrack(val track: Track) : Target
    class OpenList(val list: YtList) : Target
    class OpenArtist(val artist: ArtistRef) : Target
    class OpenPodcast(val feedUrl: String, val name: String) : Target
    class Search(val query: String) : Target

    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'', '>')
    }

    fun isSupported(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return false
        return host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("spotify.com") ||
            host == "spotify.link" || host == "podcasts.apple.com"
    }

    /** Works out what a link points to. Runs network requests, so call it off the main thread. */
    fun resolve(url: String): Target? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return when {
            host.endsWith("youtube.com") || host == "youtu.be" -> youtube(url, uri, host)
            host.endsWith("spotify.com") || host == "spotify.link" -> spotify(url)
            host == "podcasts.apple.com" -> apple(uri)
            else -> null
        }
    }

    private fun youtube(url: String, uri: Uri, host: String): Target? {
        val kind = if (host.startsWith("music.")) Kind.MUSIC else Kind.VIDEO
        val path = uri.path ?: ""
        if (path.startsWith("/channel/") || path.startsWith("/@") || path.startsWith("/c/") || path.startsWith("/user/")) {
            return OpenArtist(ArtistRef("", url))
        }
        val list = uri.getQueryParameter("list")
        val video = if (path.startsWith("/playlist")) null else YouTube.videoId(url)
        if (!video.isNullOrEmpty()) {
            return PlayTrack(YouTube.trackFor(video, kind).copy(link = url))
        }
        if (list != null) {
            return OpenList(YtList("", "", "https://www.youtube.com/playlist?list=$list", -1, kind))
        }
        return null
    }

    private fun apple(uri: Uri): Target? {
        val id = Regex("id(\\d+)").find(uri.path ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val p = Podcasts.lookup(listOf(id)).firstOrNull() ?: return null
        return OpenPodcast(p.feedUrl, p.name)
    }

    private fun spotify(url: String): Target? {
        val (finalUrl, meta) = Net.get(url).use { response ->
            response.request.url.toString() to metaTags(response.body?.string() ?: "")
        }
        val segments = Uri.parse(finalUrl).pathSegments.filterNot { it.startsWith("intl-") }
        val type = segments.firstOrNull() ?: return null
        val title = meta["og:title"].orEmpty().trim()
        if (title.isEmpty()) return null
        val description = meta["og:description"].orEmpty()
        val parts = description.substringAfter("Spotify.").split(" · ", "·").map { it.trim() }.filter { it.isNotEmpty() }
        val artist = meta["music:musician_description"]?.trim()?.ifEmpty { null }
            ?: parts.firstOrNull { p -> p != title && !p.matches(Regex("\\d{4}")) && p.lowercase(Locale.ROOT) !in LABELS }
            ?: ""
        return when (type) {
            "track" -> {
                val hit = YtSearch("$title $artist".trim(), YtSearch.SONGS, Kind.MUSIC).first()
                    .firstNotNullOfOrNull { (it as? YtTrackResult)?.track }
                hit?.let { PlayTrack(it.copy(link = url)) } ?: Search("$title $artist".trim())
            }
            "album" -> {
                val hit = YtSearch("$title $artist".trim(), YtSearch.ALBUMS, Kind.MUSIC).first()
                    .firstNotNullOfOrNull { (it as? YtListResult)?.list }
                hit?.let { OpenList(it) } ?: Search(title)
            }
            "artist" -> YouTube.findArtist(title)?.let { OpenArtist(it) } ?: Search(title)
            "playlist" -> {
                val hit = YtSearch(title, YtSearch.MUSIC_PLAYLISTS, Kind.MUSIC).first()
                    .firstNotNullOfOrNull { (it as? YtListResult)?.list }
                hit?.let { OpenList(it) } ?: Search(title)
            }
            "episode" -> {
                val show = parts.firstOrNull { it != title }.orEmpty()
                val eps = Podcasts.searchEpisodes(title)
                val hit = eps.firstOrNull { show.isNotEmpty() && Podcasts.norm(it.artist).contains(Podcasts.norm(show)) }
                    ?: eps.firstOrNull { Podcasts.norm(it.title) == Podcasts.norm(title) }
                hit?.let { PlayTrack(it.copy(link = url)) } ?: Search(title)
            }
            "show" -> Podcasts.search(title).firstOrNull()?.let { OpenPodcast(it.feedUrl, it.name) } ?: Search(title)
            else -> Search(title)
        }
    }

    private val LABELS = setOf("song", "single", "album", "ep", "שיר", "סינגל", "אלבום", "podcast", "episode")

    /** Reads the og:/music: tags a page uses for link previews. */
    private fun metaTags(html: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("<meta\\s+[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val attrs = HashMap<String, String>()
            Regex("([\\w:-]+)\\s*=\\s*\"([^\"]*)\"").findAll(tag.value).forEach { m ->
                attrs[m.groupValues[1].lowercase(Locale.ROOT)] = m.groupValues[2]
            }
            val key = attrs["property"] ?: attrs["name"] ?: return@forEach
            val content = attrs["content"] ?: return@forEach
            if (key !in out) out[key] = HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        }
        return out
    }
}
