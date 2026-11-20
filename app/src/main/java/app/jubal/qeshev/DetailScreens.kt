package app.jubal.qeshev

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ====================================================================== Artist (discography, like N-Zik)

class ArtistScreen(act: MainActivity, private var artist: ArtistRef) : ListScreen(act) {
    override val title: String get() = artist.name.ifBlank { "אמן" }
    override val topIcon = R.drawable.ic_share
    override val topLabel = "שיתוף"
    override fun onTopAction() {
        val link = if (artist.url.isNotBlank()) YouTube.channelUrl(artist.url).replace("www.youtube.com", "music.youtube.com") else ""
        if (link.isNotBlank()) act.linkActions(artist.name, link) else act.toast("אין קישור לאמן הזה")
    }

    private var page: ArtistPage? = null
    private var top: List<Pair<Track, Long>>? = null
    private var error = false
    private var loading = false
    private var expanded = false
    private var busy = false

    private val context: PlayContext get() = PlayContext(PlayContext.ARTIST, artist.name, "אמן", artist.url, Kind.MUSIC, artistUrl = artist.url)

    override fun onShown() {
        super.onShown()
        if (page == null && !loading) load()
    }

    private fun load() {
        loading = true
        error = false
        redraw()
        act.lifecycleScope.launch {
            try {
                val ref = if (artist.url.isBlank()) {
                    withContext(Dispatchers.IO) { YouTube.findArtist(artist.name) } ?: throw IllegalStateException("not found")
                } else {
                    artist
                }
                coroutineScope {
                    val pageJob = async(Dispatchers.IO) { YouTube.artistPage(ref.url, ref.name) }
                    val loadedPage = pageJob.await()
                    artist = ArtistRef(loadedPage.name, loadedPage.url, loadedPage.subscribers)
                    page = loadedPage
                    act.refreshTop(this@ArtistScreen)
                    redraw()
                    top = runCatching { YouTube.topSongs(artist, 10) }.getOrDefault(emptyList())
                }
            } catch (e: Exception) {
                error = true
            }
            loading = false
            redraw()
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        val p = page
        val following = Store.isFollowing(artist)
        items += HeroItem(
            title = artist.name.ifBlank { "אמן" },
            subtitle = if ((p?.subscribers ?: -1L) > 0L) "${Format.big(p!!.subscribers)} מנויים" else "",
            tileKey = artist.name,
            round = true,
            kind = "אמן",
            buttons = listOf(
                HeroButton("נגן הכול", R.drawable.ic_play_arrow_fill, HeroButton.FILLED) { playAllAlbums() },
                HeroButton("ערבב", R.drawable.ic_shuffle, HeroButton.TONAL) { shuffleAll() },
                HeroButton(if (following) "עוקב" else "עקוב", if (following) R.drawable.ic_check_circle else 0, HeroButton.OUTLINED) {
                    val now = Store.toggleFollow(artist)
                    act.toast(if (now) "עכשיו אתה עוקב אחרי ${artist.name}" else "הפסקת לעקוב")
                },
            ),
        )
        if (p == null) {
            items += if (error) MessageItem("לא הצלחתי לטעון את דף האמן. בדוק את החיבור ונסה שוב.") else LoadingItem
            if (error) items += ButtonsItem(listOf("נסה שוב" to { load() }))
            adapter.items = items
            return
        }

        val songs = top
        items += HeaderItem("פופולריים")
        when {
            songs == null -> items += LoadingItem
            songs.isEmpty() -> items += MessageItem("לא נמצאו שירים.")
            else -> {
                val tracks = songs.map { it.first }
                val shown = if (expanded) songs else songs.take(5)
                shown.forEachIndexed { i, (t, plays) ->
                    val sub = Format.join(if (plays >= 0) "${Format.big(plays)} השמעות" else "", Format.duration(t.durationSec), status(t))
                    items += row(t, sub, all = tracks, index = i, context = context, number = i + 1)
                }
                if (songs.size > 5) {
                    items += ButtonsItem(listOf((if (expanded) "הצג פחות" else "הצג עוד") to { expanded = !expanded; render() }))
                }
            }
        }

        if (p.singles.isNotEmpty()) {
            items += HeaderItem("סינגלים חדשים")
            items += ShelfItem("singles:${artist.key}", p.singles.take(15).map { albumCard(it) })
        }
        if (p.albums.isNotEmpty()) {
            items += HeaderItem("אלבומים")
            items += ShelfItem("albums:${artist.key}", p.albums.map { l ->
                Card(
                    l.title, if (l.count > 0) "${l.count} שירים" else "אלבום", l.artist + l.title,
                    icon = R.drawable.ic_album,
                    onClick = { act.openAlbum(l) }, onMore = { act.listActions(l, null) },
                )
            })
        }
        val similar = Recs.similarTo(artist, 10)
        if (similar.isNotEmpty()) {
            items += HeaderItem("אמנים דומים")
            items += ShelfItem("similar:${artist.key}", similar.map { artistCard(it) })
        }
        if (p.singles.isEmpty() && p.albums.isEmpty() && songs?.isEmpty() == true) {
            items += MessageItem("לא נמצאו אלבומים לאמן הזה.")
        }
        adapter.items = items
    }

    /** All albums one after another, oldest first. The first album starts right away, the rest join the queue as they load. */
    private fun playAllAlbums() {
        val p = page ?: return
        val albums = p.albums.reversed()
        if (albums.isEmpty()) {
            val tracks = top?.map { it.first }.orEmpty()
            if (tracks.isNotEmpty()) act.playAll(tracks, context) else act.toast("עדיין טוען…")
            return
        }
        if (busy) return
        busy = true
        act.toast("מנגן את כל האלבומים לפי הסדר")
        act.lifecycleScope.launch {
            var started = false
            for (album in albums) {
                val tracks = runCatching { withContext(Dispatchers.IO) { YouTube.loadList(album.url, Kind.MUSIC).second } }.getOrDefault(emptyList())
                if (tracks.isEmpty()) continue
                if (!started) {
                    act.playAll(tracks, context)
                    started = true
                } else {
                    act.addAllToQueue(tracks, quiet = true)
                }
            }
            if (!started) act.toast("לא הצלחתי לטעון את האלבומים")
            busy = false
        }
    }

    private fun shuffleAll() {
        val p = page ?: return
        if (busy) return
        busy = true
        act.toast("מערבב את כל השירים…")
        act.lifecycleScope.launch {
            val all = (top?.map { it.first }.orEmpty() + YouTube.loadAlbums(p.albums + p.singles)).distinctBy { it.id }
            if (all.isEmpty()) act.toast("לא נמצאו שירים") else act.shuffle(all, context)
            busy = false
        }
    }
}

// ====================================================================== Album or playlist

class AlbumScreen(act: MainActivity, private var album: YtList) : ListScreen(act) {
    override val title: String get() = album.title.ifBlank { "רשימה" }
    override val topIcon = R.drawable.ic_more_vert
    override val topLabel = "אפשרויות"
    override fun onTopAction() = act.listActions(album, tracks)

    private var tracks: List<Track>? = null
    private var error = false

    private val isAlbum: Boolean get() = album.url.contains("OLAK5uy") || album.count in 1..3
    private val context: PlayContext
        get() = PlayContext(
            if (isAlbum) PlayContext.ALBUM else PlayContext.YT_LIST, album.title, album.artist, album.url, album.kind, artistUrl = album.artistUrl,
        )

    override fun onShown() {
        super.onShown()
        if (tracks == null) load()
    }

    private fun load() {
        error = false
        redraw()
        act.lifecycleScope.launch {
            try {
                val (name, loaded) = withContext(Dispatchers.IO) { YouTube.loadList(album.url, album.kind) }
                val artist = album.artist.ifBlank { loaded.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: "" }
                album = album.copy(title = album.title.ifBlank { name }, artist = artist, count = loaded.size.toLong())
                tracks = loaded
                act.refreshTop(this@AlbumScreen)
            } catch (e: Exception) {
                error = true
            }
            redraw()
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        val t = tracks
        val saved = Store.isAlbumSaved(album.url)
        val total = t?.sumOf { it.durationSec } ?: 0
        items += HeroItem(
            title = album.title.ifBlank { "רשימה" },
            subtitle = Format.join(album.artist, if (t != null) "${t.size} שירים" else "", if (total > 0) Format.duration(total) else ""),
            tileKey = album.artist.ifBlank { album.title } + album.title,
            icon = if (isAlbum) R.drawable.ic_album else R.drawable.ic_queue_music,
            kind = if (!isAlbum) "פלייליסט" else if (album.count in 1..3) "סינגל" else "אלבום",
            buttons = listOf(
                HeroButton("נגן", R.drawable.ic_play_arrow_fill, HeroButton.FILLED) { t?.let { act.playAll(it, context) } },
                HeroButton("ערבב", R.drawable.ic_shuffle, HeroButton.TONAL) { t?.let { act.shuffle(it, context) } },
                HeroButton(if (saved) "בספרייה" else "שמור", if (saved) R.drawable.ic_library_add_check else R.drawable.ic_library_add, HeroButton.OUTLINED) {
                    val now = Store.toggleAlbum(album)
                    act.toast(if (now) "נשמר בספרייה" else "הוסר מהספרייה")
                },
            ),
            onSubtitle = if (album.artist.isNotBlank()) ({ act.openArtist(ArtistRef(album.artist, album.artistUrl)) }) else null,
        )
        when {
            t == null && error -> {
                items += MessageItem("לא הצלחתי לפתוח את הרשימה.")
                items += ButtonsItem(listOf("נסה שוב" to { load() }))
            }
            t == null -> items += LoadingItem
            t.isEmpty() -> items += MessageItem("הרשימה ריקה.")
            else -> t.forEachIndexed { i, track ->
                items += row(track, Format.join(track.artist, Format.duration(track.durationSec), status(track)), all = t, index = i,
                    context = context, number = if (isAlbum) i + 1 else 0)
            }
        }
        adapter.items = items
    }
}

// ====================================================================== Podcast

class PodcastScreen(act: MainActivity, private val feedUrl: String, private var name: String) : ListScreen(act) {
    override val title: String get() = name
    override val topIcon = R.drawable.ic_share
    override val topLabel = "שיתוף"
    override fun onTopAction() {
        val link = Store.podcastMeta(feedUrl)?.appleUrl()?.ifBlank { null } ?: feedUrl
        act.linkActions(name, link)
    }

    private var feed: Feed? = null
    private var error = false
    private var loading = false
    private var similar: List<PodcastResult>? = null

    override fun onShown() {
        super.onShown()
        if (feed == null) load()
    }

    private fun load() {
        if (loading) return
        loading = true
        error = false
        redraw()
        act.lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Podcasts.loadFeed(feedUrl) }
                feed = f
                name = f.title
                act.refreshTop(this@PodcastScreen)
            } catch (e: Exception) {
                error = true
            }
            loading = false
            redraw()
            loadSimilar()
        }
    }

    private fun loadSimilar() {
        if (similar != null) return
        act.lifecycleScope.launch {
            var meta = Store.podcastMeta(feedUrl)
            if (meta == null || meta.genreIds.isEmpty()) {
                meta = runCatching {
                    withContext(Dispatchers.IO) { Podcasts.search(name).firstOrNull { it.feedUrl == feedUrl }?.ref() }
                }.getOrNull() ?: meta
            }
            val genres = meta?.genreIds.orEmpty()
            similar = if (genres.isEmpty()) emptyList() else Recs.similarPodcasts(genres, setOf(feedUrl), 12)
            redraw()
        }
    }

    private fun author(f: Feed?): String = f?.author?.ifBlank { null } ?: Store.podcastMeta(feedUrl)?.author ?: ""

    override fun render() {
        val items = ArrayList<Item>()
        val f = feed
        val following = Store.isSubscribed(feedUrl)
        val by = author(f)
        val tracks = if (f != null) f.episodes.map { it.toTrack(f.title, feedUrl, by) } else emptyList()
        items += HeroItem(
            title = f?.title ?: name,
            subtitle = by,
            tileKey = f?.title ?: name,
            icon = R.drawable.ic_podcasts,
            kind = "הסכת",
            buttons = if (f == null) emptyList() else listOf(
                HeroButton("האחרון", R.drawable.ic_play_arrow_fill, HeroButton.FILLED) {
                    tracks.maxByOrNull { it.date }?.let { act.playOne(it) }
                },
                HeroButton(if (following) "עוקב" else "עקוב", if (following) R.drawable.ic_check_circle else 0, HeroButton.OUTLINED) {
                    if (following) {
                        Store.unsubscribe(feedUrl)
                    } else {
                        val meta = Store.podcastMeta(feedUrl)
                        Store.subscribe(meta?.copy(title = f.title, author = by) ?: PodcastRef(f.title, by, feedUrl))
                    }
                    Store.clearCache("pods")
                    act.toast(if (following) "הוסר מההסכתים שלך" else "נוסף להסכתים שלך")
                },
                HeroButton("על ההסכת", R.drawable.ic_info, HeroButton.TONAL) { act.showText(f.title, Podcasts.plainText(f.rawDescription)) },
            ),
            onSubtitle = if (by.isNotBlank()) ({ act.openPodcaster(by) }) else null,
        )
        if (f == null) {
            items += if (error) MessageItem("לא הצלחתי לטעון את ההסכת. בדוק את החיבור ונסה שוב.") else LoadingItem
            if (error) items += ButtonsItem(listOf("נסה שוב" to { load() }))
            adapter.items = items
            return
        }
        val sim = similar
        if (!sim.isNullOrEmpty()) {
            items += HeaderItem("הסכתים דומים")
            items += ShelfItem("sim:$feedUrl", sim.map { podcastCard(it.name, it.author, it.feedUrl) })
        }
        items += HeaderItem(if (tracks.isEmpty()) "אין פרקים" else "${tracks.size} פרקים", "הוסף לפלייליסט") { act.chooseFolder(tracks) }
        f.episodes.forEachIndexed { i, e ->
            items += row(tracks[i], description = Podcasts.plainText(e.rawDescription))
        }
        adapter.items = items
    }
}

// ====================================================================== Podcaster (all their shows, and similar ones)

class PodcasterScreen(act: MainActivity, private val author: String) : ListScreen(act) {
    override val title: String get() = author

    private var shows: List<PodcastResult>? = null
    private var similar: List<PodcastResult>? = null
    private var error = false

    override fun onShown() {
        super.onShown()
        if (shows == null) load()
    }

    private fun load() {
        error = false
        redraw()
        act.lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { Podcasts.byAuthor(author) }
                shows = found
                redraw()
                val genres = found.flatMap { it.genreIds }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
                similar = Recs.similarPodcasts(genres, found.map { it.feedUrl }.toSet(), 15)
            } catch (e: Exception) {
                error = true
            }
            redraw()
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        val s = shows
        items += HeroItem(
            author, if (s != null) Format.count(s.size, "הסכת", "הסכתים") else "", author,
            icon = R.drawable.ic_mic, kind = "יוצר הסכתים",
        )
        when {
            s == null && error -> {
                items += MessageItem("לא הצלחתי לטעון. בדוק את החיבור ונסה שוב.")
                items += ButtonsItem(listOf("נסה שוב" to { load() }))
            }
            s == null -> items += LoadingItem
            s.isEmpty() -> items += MessageItem("לא נמצאו הסכתים של $author.")
            else -> {
                items += HeaderItem("ההסכתים של $author")
                s.forEach { p ->
                    items += EntityItem(
                        p.name, Format.join(p.genres.firstOrNull() ?: "", if (p.episodeCount > 0) "${p.episodeCount} פרקים" else ""), p.name,
                        R.drawable.ic_podcasts,
                        onClick = { act.openPodcast(p.feedUrl, p.name) },
                    )
                }
            }
        }
        val sim = similar
        if (!sim.isNullOrEmpty()) {
            items += HeaderItem("הסכתים דומים")
            items += ShelfItem("sim:$author", sim.map { podcastCard(it.name, it.author, it.feedUrl) })
        }
        adapter.items = items
    }
}

// ====================================================================== Liked, history, downloads, playlists

class CollectionScreen(act: MainActivity, private val type: Int, private val folderId: String = "") : ListScreen(act) {
    override val title: String
        get() = when (type) {
            LIKED -> "אהבתי"
            DOWNLOADS -> "שמור בטלפון"
            HISTORY -> "הושמע לאחרונה"
            else -> Store.folder(folderId)?.name ?: "פלייליסט"
        }

    override val topIcon: Int
        get() = when (type) {
            PLAYLIST -> R.drawable.ic_more_vert
            HISTORY -> R.drawable.ic_delete
            else -> 0
        }

    override val topLabel: String get() = if (type == HISTORY) "ניקוי היסטוריה" else "אפשרויות"

    override fun onTopAction() {
        when (type) {
            PLAYLIST -> Store.folder(folderId)?.let { f -> LibraryScreen.playlistActions(act, f) { act.goBack() } }
            HISTORY -> act.confirm("לנקות את ההיסטוריה?", "גם \"המשך האזנה\" יתרוקן.", "ניקוי") { Store.clearHistory() }
        }
    }

    private val context: PlayContext?
        get() = when (type) {
            LIKED -> PlayContext.liked()
            PLAYLIST -> Store.folder(folderId)?.let { PlayContext(PlayContext.PLAYLIST, it.name, "פלייליסט", it.id, Kind.MUSIC) }
            else -> null
        }

    private fun tracks(): List<Track> = when (type) {
        LIKED -> Store.favorites()
        DOWNLOADS -> Store.downloads().map { it.track }
        HISTORY -> Store.history().map { it.track }
        else -> Store.folder(folderId)?.tracks ?: emptyList()
    }

    override fun render() {
        act.refreshTop(this)
        if (type == PLAYLIST && Store.folder(folderId) == null) {
            adapter.items = listOf(MessageItem("הפלייליסט נמחק."))
            return
        }
        val t = tracks()
        val items = ArrayList<Item>()
        val total = t.sumOf { it.durationSec }
        items += HeroItem(
            title = title,
            subtitle = Format.join(Format.count(t.size, "פריט", "פריטים"), if (total > 0) Format.duration(total) else ""),
            tileKey = title,
            brand = type == LIKED,
            icon = when (type) {
                LIKED -> R.drawable.ic_favorite_fill
                DOWNLOADS -> R.drawable.ic_download_done
                HISTORY -> R.drawable.ic_history
                else -> R.drawable.ic_queue_music
            },
            kind = when (type) {
                DOWNLOADS -> "הורדות"
                HISTORY -> "היסטוריה"
                else -> "פלייליסט"
            },
            buttons = if (t.isEmpty()) emptyList() else listOf(
                HeroButton("נגן", R.drawable.ic_play_arrow_fill, HeroButton.FILLED) { act.playAll(t, context) },
                HeroButton("ערבב", R.drawable.ic_shuffle, HeroButton.TONAL) { act.shuffle(t, context) },
            ),
        )
        if (t.isEmpty()) {
            items += MessageItem(
                when (type) {
                    LIKED -> "עוד אין כאן כלום. בכל שיר, פרק או שמע מיוטיוב, או בנגן, לחץ על הלב."
                    DOWNLOADS -> "עוד לא שמרת כלום. בכל פריט אפשר לבחור \"שמור בטלפון\", ואז הוא יעבוד גם בלי אינטרנט."
                    HISTORY -> "עוד לא שמעת כלום."
                    else -> "הפלייליסט ריק. מכל פריט אפשר לבחור \"הוסף לפלייליסט\"."
                }
            )
            adapter.items = items
            return
        }
        if (type == DOWNLOADS) {
            items += MessageItem("הקבצים נמצאים בזיכרון הטלפון, בתיקיות Music/Qeshev ו-Podcasts/Qeshev.")
        }
        if (type == PLAYLIST || type == LIKED) {
            items += ButtonsItem(listOf("שמור הכול בטלפון" to { act.downloadAll(t) }, "הוסף הכול לתור" to { act.addAllToQueue(t) }))
        }
        t.forEachIndexed { i, track ->
            val extra = when (type) {
                PLAYLIST -> listOf(SheetAction(R.drawable.ic_close, "הסר מהפלייליסט") { Store.removeFromFolder(folderId, track.id) })
                HISTORY -> listOf(SheetAction(R.drawable.ic_close, "הסר מההיסטוריה") { Store.removeHistory(track.id) })
                else -> emptyList()
            }
            items += row(track, all = t, index = i, extra = extra, context = context)
        }
        adapter.items = items
    }

    companion object {
        const val LIKED = 0
        const val DOWNLOADS = 1
        const val HISTORY = 2
        const val PLAYLIST = 3
    }
}
