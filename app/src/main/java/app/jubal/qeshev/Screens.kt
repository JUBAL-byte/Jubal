package app.jubal.qeshev

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One page of the app. Its view is built once and kept, so going back keeps your place. */
abstract class Screen(protected val act: MainActivity) {
    abstract val title: String
    open val topIcon: Int = 0
    open val topLabel: String = ""
    open fun onTopAction() {}

    private var root: View? = null
    fun view(): View = root ?: build().also { root = it }
    protected abstract fun build(): View
    open fun onShown() {}
    open fun onHidden() {}
    open fun onPlayerItemChanged() {}
    /** The tab was tapped again while already showing this page. */
    open fun onReselected() {}
    /** "Back" inside the page (clear a search, reset a filter). False when the page is already as it opens. */
    open fun onBack(): Boolean = false
}

/** A screen made of one list, redrawn whenever saved data changes. */
abstract class ListScreen(act: MainActivity) : Screen(act) {
    protected val adapter = ItemsAdapter { act.currentId }
    protected lateinit var list: RecyclerView
    protected var visible = false
    private val storeListener: () -> Unit = { if (visible) render() }

    override fun build(): View {
        list = act.recycler(adapter)
        return list
    }

    override fun onShown() {
        visible = true
        Store.addListener(storeListener)
        render()
    }

    override fun onHidden() {
        visible = false
        Store.removeListener(storeListener)
    }

    override fun onPlayerItemChanged() {
        adapter.notifyDataSetChanged()
    }

    override fun onReselected() {
        if (::list.isInitialized) list.smoothScrollToPosition(0)
    }

    abstract fun render()

    protected fun redraw() {
        if (visible) render()
    }

    protected fun defaultSubtitle(t: Track): String = when (t.kind) {
        Kind.PODCAST -> Format.join(t.artist, Format.date(t.date), Format.duration(t.durationSec), status(t))
        else -> Format.join(t.artist, Format.duration(t.durationSec), status(t))
    }

    protected fun status(t: Track): String = when {
        Store.downloadFor(t.id) != null -> "שמור"
        t.kind != Kind.MUSIC && Store.isDone(t.id) -> "נשמע"
        else -> ""
    }

    private fun progressOf(t: Track): Int =
        if (t.kind == Kind.MUSIC) -1 else Store.progress(t.id, t.durationSec).takeIf { it in 1..999 } ?: -1

    /** A row that plays [t] (or the whole [all] list from [index]) and offers the usual actions. */
    protected fun row(
        t: Track,
        subtitle: String = defaultSubtitle(t),
        all: List<Track>? = null,
        index: Int = 0,
        extra: List<SheetAction> = emptyList(),
        description: String? = null,
        context: PlayContext? = null,
        number: Int = 0,
    ): TrackItem = TrackItem(
        track = t,
        subtitle = subtitle,
        progress = progressOf(t),
        number = number,
        onClick = { if (all != null) act.playTracks(all, index, context, tapped = true) else act.playOne(t) },
        onMore = { act.trackActions(t, extra, description) },
    )

    protected fun trackCard(t: Track, subtitle: String = t.artist, all: List<Track>? = null, index: Int = 0): Card = Card(
        title = t.title,
        subtitle = subtitle,
        tileKey = t.artist.ifBlank { t.title },
        progress = progressOf(t),
        id = t.id,
        onClick = { if (all != null) act.playTracks(all, index, null, tapped = true) else act.playOne(t) },
        onMore = { act.trackActions(t) },
    )

    protected fun albumCard(l: YtList): Card = Card(
        title = l.title,
        subtitle = Format.join(if (l.count in 1..3) "סינגל" else "אלבום", l.artist),
        tileKey = l.artist.ifBlank { l.title } + l.title,
        icon = R.drawable.ic_album,
        onClick = { act.openAlbum(l) },
        onMore = { act.listActions(l, null) },
    )

    protected fun artistCard(a: ArtistRef, subtitle: String = "אמן"): Card = Card(
        title = a.name,
        subtitle = subtitle,
        tileKey = a.name,
        round = true,
        onClick = { act.openArtist(a) },
    )

    protected fun podcastCard(title: String, author: String, feedUrl: String): Card = Card(
        title = title,
        subtitle = author.ifBlank { "הסכת" },
        tileKey = title,
        icon = R.drawable.ic_podcasts,
        onClick = { act.openPodcast(feedUrl, title) },
    )

    protected fun contextCard(ctx: PlayContext): Card = Card(
        title = ctx.title,
        subtitle = contextLabel(ctx),
        tileKey = contextKey(ctx),
        round = ctx.type == PlayContext.ARTIST,
        icon = contextIcon(ctx),
        brand = ctx.type == PlayContext.LIKED,
        id = if (ctx.type == PlayContext.SONG) ctx.track?.id ?: "" else "",
        onClick = { act.openContext(ctx) },
        onMore = ctx.track?.let { t -> { act.trackActions(t) } },
    )

    protected fun contextKey(ctx: PlayContext): String = when (ctx.type) {
        PlayContext.SONG -> ctx.track?.artist?.ifBlank { null } ?: ctx.title
        PlayContext.ARTIST, PlayContext.PODCAST -> ctx.title
        else -> ctx.subtitle.ifBlank { ctx.title } + ctx.title
    }

    protected fun contextLabel(ctx: PlayContext): String = when (ctx.type) {
        PlayContext.ALBUM -> Format.join("אלבום", ctx.subtitle)
        PlayContext.YT_LIST -> Format.join("פלייליסט", ctx.subtitle)
        PlayContext.PLAYLIST -> "הפלייליסט שלך"
        PlayContext.LIKED -> "השירים שאהבת"
        PlayContext.ARTIST -> "אמן"
        PlayContext.PODCAST -> Format.join("הסכת", ctx.subtitle)
        else -> Format.join(ctx.kind.label, ctx.subtitle)
    }

    protected fun contextIcon(ctx: PlayContext): Int = when (ctx.type) {
        PlayContext.ALBUM -> R.drawable.ic_album
        PlayContext.YT_LIST, PlayContext.PLAYLIST -> R.drawable.ic_queue_music
        PlayContext.LIKED -> R.drawable.ic_favorite_fill
        PlayContext.ARTIST -> R.drawable.ic_person
        PlayContext.PODCAST -> R.drawable.ic_podcasts
        else -> 0
    }
}

// ====================================================================== Home

class HomeScreen(act: MainActivity) : ListScreen(act) {
    // The name is in the strip above; the bar greets instead
    override val title: String get() = Format.greeting()
    override val topIcon = R.drawable.ic_history
    override val topLabel = "הושמע לאחרונה"
    override fun onTopAction() = act.push(CollectionScreen(act, CollectionScreen.HISTORY))

    private var filter = 0
    private var albums: List<YtList>? = Recs.cachedAlbums()
    private var pods: List<PodcastRef>? = Recs.cachedPodcasts()
    private var loadingAlbums = false
    private var loadingPods = false
    private var loadingEpisodes = false

    override fun onShown() {
        super.onShown()
        load(false)
    }

    override fun onBack(): Boolean {
        if (filter == 0) return false
        filter = 0
        render()
        list.scrollToPosition(0)
        return true
    }

    private fun load(force: Boolean) {
        if (!loadingEpisodes && Store.subscriptions().isNotEmpty()) {
            loadingEpisodes = true
            act.lifecycleScope.launch {
                runCatching { Recs.newEpisodes(force) }
                loadingEpisodes = false
                redraw()
            }
        }
        if (!loadingAlbums && (albums == null || force)) {
            loadingAlbums = true
            act.lifecycleScope.launch {
                albums = runCatching { Recs.recommendedAlbums(12, force) }.getOrDefault(albums ?: emptyList())
                loadingAlbums = false
                redraw()
            }
        }
        if (!loadingPods && (pods == null || force)) {
            loadingPods = true
            act.lifecycleScope.launch {
                pods = runCatching { Recs.recommendedPodcasts(12, force) }.getOrDefault(pods ?: emptyList())
                loadingPods = false
                redraw()
            }
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        items += ChipsItem(listOf("הכול", "מוזיקה", "הסכתים"), filter) { filter = it; render() }
        val music = filter != 2
        val podcasts = filter != 1
        var anything = false

        // What you played recently (albums, playlists, artists, podcasts, songs)
        val recent = Store.contexts().filter { (music && it.kind != Kind.PODCAST) || (podcasts && it.kind == Kind.PODCAST) }

        if (podcasts) {
            val episodes = Store.inProgress(setOf(Kind.PODCAST), 10)
            if (episodes.isNotEmpty()) {
                anything = true
                items += HeaderItem("המשך האזנה להסכתים")
                items += ShelfItem("cont-pod", episodes.map { t ->
                    trackCard(t, Format.join(t.artist, Format.remaining(Store.remainingMs(t.id, t.durationSec))))
                })
            }
        }

        if (music) {
            val musicCtx = recent.filter { it.kind == Kind.MUSIC }.take(12)
            if (musicCtx.isNotEmpty()) {
                anything = true
                items += HeaderItem("המשך האזנה למוזיקה")
                items += ShelfItem("cont-music", musicCtx.map { contextCard(it) })
            }
            val videos = Store.inProgress(setOf(Kind.VIDEO), 8)
            if (videos.isNotEmpty()) {
                anything = true
                items += HeaderItem("המשך האזנה מיוטיוב")
                items += ShelfItem("cont-video", videos.map { t ->
                    trackCard(t, Format.join(t.artist, Format.remaining(Store.remainingMs(t.id, t.durationSec))))
                })
            }

            val artists = Recs.favoriteArtists(12)
            if (artists.isNotEmpty()) {
                anything = true
                items += HeaderItem("האמנים האהובים שלך")
                items += ShelfItem("artists", artists.map { artistCard(it.artist) })
            }

            val al = albums
            if (artists.isNotEmpty() && (al == null || al.isNotEmpty() || loadingAlbums)) {
                items += HeaderItem("אלבומים מומלצים")
                if (al.isNullOrEmpty()) items += LoadingItem else items += ShelfItem("albums", al.map { albumCard(it) })
            }

            val songs = Recs.forYou(setOf(Kind.MUSIC), 12).map { it.track }
            if (songs.isNotEmpty()) {
                anything = true
                items += HeaderItem("שירים בשבילך", "נגן הכול") { act.playAll(songs, null) }
                items += ShelfItem("for-you", songs.mapIndexed { i, t -> trackCard(t, t.artist, songs, i) })
            }
            val vids = Recs.forYou(setOf(Kind.VIDEO), 10).map { it.track }
            if (vids.isNotEmpty()) {
                anything = true
                items += HeaderItem("שמע מיוטיוב בשבילך")
                items += ShelfItem("for-you-v", vids.map { trackCard(it) })
            }
        }

        if (podcasts) {
            if (Store.subscriptions().isNotEmpty()) {
                anything = true
                items += HeaderItem("פרקים חדשים מההסכתים שלך", "רענון") { load(true) }
                val eps = Recs.cachedEpisodes()
                when {
                    eps == null -> items += LoadingItem
                    eps.isEmpty() -> items += MessageItem("אין פרקים חדשים כרגע.")
                    else -> items += ShelfItem("new-eps", eps.map { trackCard(it, Format.join(it.artist, Format.date(it.date))) })
                }
            }
            val p = pods
            if (Recs.podcastGenres().isNotEmpty() && (p == null || p.isNotEmpty() || loadingPods)) {
                items += HeaderItem("הסכתים שאולי תאהב")
                if (p.isNullOrEmpty()) items += LoadingItem else items += ShelfItem("pods", p.map { podcastCard(it.title, it.author, it.feedUrl) })
            }
        }

        if (!anything) {
            items += MessageItem(
                when (filter) {
                    1 -> "כשתשמע שירים, יופיעו כאן האמנים שאתה אוהב, אלבומים מומלצים ושירים בשבילך."
                    2 -> "עקוב אחרי הסכתים כדי לראות כאן פרקים חדשים והמלצות."
                    else -> "ברוך הבא ל-JUBAL.\nאפשר לשמוע כאן מוזיקה, הסכתים וכל דבר מיוטיוב. הכול בשמע בלבד, בלי תמונות ובלי וידאו.\n" +
                        "ההמלצות לומדות רק ממה שבחרת לשמוע בעצמך: מה שמתנגן לבד כחלק מתור או פלייליסט לא נחשב."
                }
            )
            items += ButtonsItem(listOf("לחיפוש" to { act.selectTab(1) }))
        }
        adapter.items = items
    }
}

// ====================================================================== Search

class SearchScreen(act: MainActivity) : ListScreen(act) {
    override val title = "חיפוש"

    private lateinit var input: EditText
    private var query = ""
    private var filter = 0
    private var generation = 0
    private var message = ""
    private var suggestion = ""
    private var suppressWatcher = false
    private val debounce = Handler(Looper.getMainLooper())

    // "All" results
    private var songs: List<Track> = emptyList()
    private var artists: List<ArtistRef> = emptyList()
    private var albumsFound: List<YtList> = emptyList()
    private var podcastsFound: List<PodcastResult> = emptyList()
    private var videos: List<Track> = emptyList()
    private var playlists: List<YtList> = emptyList()
    // Single-filter results
    private var ytSearch: YtSearch? = null
    private val ytResults = ArrayList<YtResult>()
    private var episodes: List<Track> = emptyList()
    private var loading = false
    private var loadingMore = false

    private val filters = listOf("הכול", "שירים", "אמנים", "אלבומים", "פלייליסטים", "הסכתים", "פרקים", "יוטיוב")

    private class Category(val title: String, val query: String, val filter: Int, val keywords: List<String> = emptyList())

    private val musicCategories = listOf(
        Category("פופ", "pop hits", 4),
        Category("רוק", "rock", 4),
        Category("היפ הופ", "hip hop", 4),
        Category("ישראלי", "מוזיקה ישראלית", 4),
        Category("מזרחית", "מוזיקה מזרחית", 4),
        Category("אלקטרונית", "electronic music", 4),
        Category("ג׳אז", "jazz", 4),
        Category("קלאסית", "classical music", 4),
        Category("אינדי", "indie", 4),
        Category("שנות ה־80", "80s hits", 4),
        Category("אקפלה", "a cappella", 4),
        Category("רגוע", "relaxing music", 4),
        Category("לריכוז", "focus music", 4),
        Category("לאימון", "workout music", 4),
    )

    private val podcastCategories = listOf(
        Category("שיעורי תורה", "שיעורי תורה", 5),
        Category("יהדות", "יהדות", 5),
        Category("היסטוריה", "היסטוריה", 5),
        Category("מדע", "מדע", 5),
        Category("טכנולוגיה", "טכנולוגיה", 5),
        Category("כלכלה ועסקים", "כלכלה", 5),
        Category("חדשות", "חדשות", 5),
        Category("ספורט", "ספורט", 5),
        Category("פסיכולוגיה", "פסיכולוגיה", 5),
        Category("בריאות", "בריאות", 5),
    )

    private val youtubeCategories = listOf(
        Category("הרצאות", "הרצאה", 7),
        Category("ספרים מוקלטים", "ספר מוקלט", 7),
        Category("ראיונות", "ראיון", 7),
        Category("סיפורים", "סיפור", 7),
    )

    // Offered only to people who listen to this kind of music
    private val shabbat = Category(
        "שירי שבת", "שירי שבת", 4,
        listOf("שבת", "shabbat", "shabbos", "זמירות", "zemirot", "לכה דודי", "lecha dodi", "שלום עליכם", "יה ריבון", "yah ribon"),
    )
    private val hasidic = Category(
        "מוזיקה חסידית", "מוזיקה חסידית", 4,
        listOf(
            "חסידי", "hasidic", "chassidic", "chasidic", "ניגון", "nigun", "niggun", "קרליבך", "carlebach",
            "מרדכי בן דוד", "mordechai ben david", "אברהם פריד", "avraham fried", "בני פרידמן", "benny friedman",
            "מוטי שטיינמץ", "motty steinmetz", "שוואקי", "shwekey", "ליפא", "lipa schmeltzer", "ביני לנדאו",
            "beri weber", "שלומי גרטנר", "shloime gertner", "חיים ישראל", "יידיש", "yiddish",
        ),
    )
    private val piyutim = Category(
        "פיוטים", "פיוטים", 4,
        listOf("פיוט", "piyut", "piyyut", "בקשות", "ידיד נפש", "yedid nefesh", "אנעים זמירות", "דרור יקרא", "אדון עולם", "adon olam"),
    )
    private val jewish = Category(
        "מוזיקה יהודית", "מוזיקה יהודית", 4,
        listOf(
            "jewish", "יהודי", "עם ישראל חי", "am yisrael chai", "ישי ריבו", "ishay ribo", "חנן בן ארי", "hanan ben ari",
            "אהרן רזאל", "aaron razel", "יונתן רזאל", "yonatan razel", "שולי רנד", "shuli rand", "אבישי רוזן",
        ),
    )

    /** Categories that match what you listen to: two songs, or one followed artist. */
    private fun yourCategories(): List<Category> {
        val heard = Recs.chosenMusic()
        if (heard.isEmpty()) return emptyList()
        fun listens(c: Category): Boolean =
            heard.filter { (text, _) -> c.keywords.any { text.contains(it) } }.sumOf { it.second } >= 2
        val out = listOf(shabbat, hasidic, piyutim).filter { listens(it) }.toMutableList()
        if (out.isNotEmpty() || listens(jewish)) out += jewish
        return out
    }

    override fun build(): View {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val box = LayoutInflater.from(act).inflate(R.layout.view_search, root, false)
        input = box.findViewById(R.id.search_input)
        input.hint = "מה בא לך לשמוע?"
        input.withoutWebActions()
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submit(record = true)
                true
            } else {
                false
            }
        }
        input.doAfterTextChanged { text ->
            if (suppressWatcher) return@doAfterTextChanged
            debounce.removeCallbacksAndMessages(null)
            val t = text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) {
                if (query.isNotEmpty()) {
                    query = ""
                    generation++
                    render()
                }
            } else if (t.length >= 2) {
                // Search as you type, like Spotify, after a short pause
                debounce.postDelayed({ submit(record = false) }, 700)
            }
        }
        root.addView(box)
        list = act.recycler(adapter)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onHidden() {
        super.onHidden()
        debounce.removeCallbacksAndMessages(null)
    }

    override fun onBack(): Boolean {
        if (!::input.isInitialized || (query.isEmpty() && input.text.isNullOrEmpty() && filter == 0)) return false
        debounce.removeCallbacksAndMessages(null)
        suppressWatcher = true
        input.setText("")
        suppressWatcher = false
        query = ""
        filter = 0
        generation++
        loading = false
        message = ""
        suggestion = ""
        render()
        list.scrollToPosition(0)
        return true
    }

    /** Fills the box and searches, e.g. for a Spotify link that had no exact match. */
    fun searchFor(q: String, withFilter: Int = filter) {
        view()
        suppressWatcher = true
        input.setText(q)
        input.setSelection(input.text.length)
        suppressWatcher = false
        filter = withFilter
        runSearch(q, record = true)
    }

    private fun submit(record: Boolean) {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        if (record) act.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        if (q.startsWith("http://") || q.startsWith("https://")) {
            if (record) act.openLink(q)
            return
        }
        if (q == query && !record) return
        runSearch(q, record)
    }

    private fun runSearch(q: String, record: Boolean) {
        debounce.removeCallbacksAndMessages(null)
        if (record) Store.addSearch(q)
        query = q
        val gen = ++generation
        message = ""
        suggestion = ""
        loading = true
        ytSearch = null
        ytResults.clear()
        render()
        act.lifecycleScope.launch {
            try {
                when (filter) {
                    0 -> searchAll(q, gen)
                    5 -> {
                        val found = withContext(Dispatchers.IO) { Podcasts.search(q) }
                        if (gen != generation) return@launch
                        podcastsFound = found
                        if (found.isEmpty()) message = "לא נמצאו הסכתים."
                    }
                    6 -> {
                        val found = withContext(Dispatchers.IO) { Podcasts.searchEpisodes(q) }
                        if (gen != generation) return@launch
                        episodes = found
                        if (found.isEmpty()) message = "לא נמצאו פרקים."
                    }
                    else -> {
                        val (code, kind) = when (filter) {
                            1 -> YtSearch.SONGS to Kind.MUSIC
                            2 -> YtSearch.ARTISTS to Kind.MUSIC
                            3 -> YtSearch.ALBUMS to Kind.MUSIC
                            4 -> YtSearch.MUSIC_PLAYLISTS to Kind.MUSIC
                            else -> YtSearch.VIDEOS to Kind.VIDEO
                        }
                        // Also try the spelling with a geresh (גימבו גיי → ג'ימבו ג'יי)
                        val qk = Spelling.key(q)
                        val typed = YtSearch(q, code, kind)
                        val variant = Spelling.withGeresh(q)?.let { YtSearch(it, code, kind) }
                        val (typedR, variantR) = withContext(Dispatchers.IO) {
                            coroutineScope {
                                val x = async { typed.first() }
                                val y = async { variant?.let { v -> runCatching { v.first() }.getOrNull() } ?: emptyList() }
                                x.await() to y.await()
                            }
                        }
                        if (gen != generation) return@launch
                        val variantWins = variant != null &&
                            variantR.count { Spelling.fit(it, qk) == 2 } > typedR.count { Spelling.fit(it, qk) == 2 }
                        ytSearch = if (variantWins) variant else typed
                        ytResults.clear()
                        ytResults += if (variantWins) Spelling.merge(qk, variantR, typedR) else Spelling.merge(qk, typedR, variantR)
                        if (ytResults.none { Spelling.fit(it, qk) == 2 }) suggestion = suggestionFor(q, typed.suggestion)
                        if (ytResults.isEmpty()) message = "לא נמצאו תוצאות."
                    }
                }
            } catch (e: Exception) {
                if (gen == generation) message = "החיפוש נכשל. בדוק את החיבור לאינטרנט ונסה שוב."
            }
            if (gen == generation) {
                loading = false
                redraw()
            }
        }
    }

    private class Found(val results: List<YtResult>, val suggestion: String)

    private suspend fun searchAll(q: String, gen: Int) = coroutineScope {
        fun yt(query: String, code: String, kind: Kind) = async(Dispatchers.IO) {
            runCatching {
                val s = YtSearch(query, code, kind)
                Found(s.first(), s.suggestion)
            }.getOrDefault(Found(emptyList(), ""))
        }
        val qk = Spelling.key(q)
        // Also try the spelling with a geresh (גימבו גיי → ג'ימבו ג'יי) for songs and artists
        val variant = Spelling.withGeresh(q)
        val s = yt(q, YtSearch.SONGS, Kind.MUSIC)
        val a = yt(q, YtSearch.ARTISTS, Kind.MUSIC)
        val sv = variant?.let { yt(it, YtSearch.SONGS, Kind.MUSIC) }
        val av = variant?.let { yt(it, YtSearch.ARTISTS, Kind.MUSIC) }
        val al = yt(q, YtSearch.ALBUMS, Kind.MUSIC)
        val v = yt(q, YtSearch.VIDEOS, Kind.VIDEO)
        val pl = yt(q, YtSearch.MUSIC_PLAYLISTS, Kind.MUSIC)
        val p = async(Dispatchers.IO) { runCatching { Podcasts.search(q) }.getOrDefault(emptyList()) }
        val songsF = s.await()
        val artistsF = a.await()
        val songsR = Spelling.merge(qk, songsF.results, sv?.await()?.results ?: emptyList())
        val artistsR = Spelling.merge(qk, artistsF.results, av?.await()?.results ?: emptyList())
        val albumsR = Spelling.merge(qk, al.await().results)
        val videosR = Spelling.merge(qk, v.await().results)
        val playlistsR = Spelling.merge(qk, pl.await().results)
        val podsR = p.await()
        if (gen != generation) return@coroutineScope
        if ((songsR + artistsR).none { Spelling.fit(it, qk) == 2 }) {
            suggestion = suggestionFor(q, songsF.suggestion.ifBlank { artistsF.suggestion })
        }
        songs = songsR.mapNotNull { (it as? YtTrackResult)?.track }
        artists = artistsR.mapNotNull { (it as? YtArtistResult)?.artist }
        albumsFound = albumsR.mapNotNull { (it as? YtListResult)?.list }
        videos = videosR.mapNotNull { (it as? YtTrackResult)?.track }
        playlists = playlistsR.mapNotNull { (it as? YtListResult)?.list }
        podcastsFound = podsR
        if (songs.isEmpty() && artists.isEmpty() && albumsFound.isEmpty() && videos.isEmpty() && podsR.isEmpty()) {
            message = "לא נמצאו תוצאות."
        }
        Unit
    }

    /** YouTube's "did you mean", unless it is just the same words. */
    private fun suggestionFor(q: String, suggested: String): String =
        suggested.takeIf { it.isNotBlank() && Spelling.key(it) != Spelling.key(q) } ?: ""

    private fun more() {
        val s = ytSearch ?: return
        if (loadingMore) return
        loadingMore = true
        val gen = generation
        redraw()
        act.lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { s.more() }
                if (gen == generation) {
                    val seen = ytResults.map { Spelling.idOf(it) }.toHashSet()
                    ytResults += found.filter { seen.add(Spelling.idOf(it)) }
                }
            } catch (e: Exception) {
                act.toast("לא הצלחתי לטעון עוד תוצאות")
            }
            loadingMore = false
            redraw()
        }
    }

    private fun setFilter(i: Int) {
        filter = i
        if (query.isNotEmpty()) runSearch(query, record = false) else render()
    }

    override fun render() {
        val items = ArrayList<Item>()
        if (query.isEmpty()) {
            val recent = Store.recentSearches()
            if (recent.isNotEmpty()) {
                items += HeaderItem("חיפושים אחרונים", "ניקוי") { Store.clearSearches() }
                recent.take(6).forEach { q ->
                    items += EntityItem(q, "", q, R.drawable.ic_history, onClick = { searchFor(q) })
                }
            }
            fun group(title: String, list: List<Category>) {
                if (list.isEmpty()) return
                items += HeaderItem(title)
                list.forEach { c -> items += CategoryItem(c.title) { searchFor(c.query, c.filter) } }
            }
            group("לפי מה שאתה שומע", yourCategories())
            group("מוזיקה", musicCategories)
            group("הסכתים", podcastCategories)
            group("מיוטיוב", youtubeCategories)
            adapter.items = items
            return
        }

        items += ChipsItem(filters, filter) { setFilter(it) }
        if (loading) {
            items += LoadingItem
            adapter.items = items
            return
        }
        val suggested = suggestion
        if (suggested.isNotEmpty()) {
            items += EntityItem("האם התכוונת: $suggested", "חיפוש לפי האיות של יוטיוב", "suggest", R.drawable.ic_search,
                onClick = { searchFor(suggested) })
        }
        if (message.isNotEmpty()) items += MessageItem(message)

        when (filter) {
            0 -> renderAll(items)
            5 -> podcastsFound.forEach { p -> items += podcastRow(p) }
            6 -> episodes.forEach { t -> items += row(t) }
            else -> {
                for (r in ytResults) {
                    when (r) {
                        is YtTrackResult -> items += row(r.track)
                        is YtListResult -> items += listRow(r.list)
                        is YtArtistResult -> items += artistRow(r.artist)
                    }
                }
                if (ytSearch?.hasMore == true && ytResults.isNotEmpty()) {
                    items += ButtonsItem(listOf((if (loadingMore) "טוען…" else "עוד תוצאות") to { more() }))
                }
            }
        }
        adapter.items = items
    }

    private fun renderAll(items: ArrayList<Item>) {
        val qk = Spelling.key(query)
        val topArtist = artists.firstOrNull { Spelling.matches(it.name, qk) }
        if (topArtist != null) {
            items += HeaderItem("התוצאה המובילה")
            items += artistRow(topArtist)
        } else if (songs.isNotEmpty()) {
            items += HeaderItem("התוצאה המובילה")
            items += row(songs.first())
        }
        val restSongs = if (topArtist == null) songs.drop(1) else songs
        if (restSongs.isNotEmpty()) {
            items += HeaderItem("שירים", "הכול") { setFilter(1) }
            restSongs.take(4).forEach { items += row(it) }
        }
        val otherArtists = artists.filter { it != topArtist }
        if (otherArtists.isNotEmpty()) {
            items += HeaderItem("אמנים", "הכול") { setFilter(2) }
            items += ShelfItem("s-artists", otherArtists.take(10).map { artistCard(it, subscribers(it)) })
        }
        if (albumsFound.isNotEmpty()) {
            items += HeaderItem("אלבומים", "הכול") { setFilter(3) }
            items += ShelfItem("s-albums", albumsFound.take(10).map { albumCard(it) })
        }
        if (podcastsFound.isNotEmpty()) {
            items += HeaderItem("הסכתים", "הכול") { setFilter(5) }
            items += ShelfItem("s-pods", podcastsFound.take(10).map { podcastCard(it.name, it.author, it.feedUrl) })
        }
        if (playlists.isNotEmpty()) {
            items += HeaderItem("פלייליסטים", "הכול") { setFilter(4) }
            items += ShelfItem("s-lists", playlists.take(10).map { l ->
                Card(l.title, l.artist, l.title, icon = R.drawable.ic_queue_music, onClick = { act.openAlbum(l) }, onMore = { act.listActions(l, null) })
            })
        }
        if (videos.isNotEmpty()) {
            items += HeaderItem("שמע מיוטיוב", "הכול") { setFilter(7) }
            videos.take(3).forEach { items += row(it) }
        }
    }

    private fun subscribers(a: ArtistRef): String =
        if (a.subscribers > 0) "${Format.big(a.subscribers)} מנויים" else "אמן"

    private fun artistRow(a: ArtistRef) =
        EntityItem(a.name, Format.join("אמן", if (a.subscribers > 0) "${Format.big(a.subscribers)} מנויים" else ""), a.name, round = true,
            onClick = { act.openArtist(a) })

    private fun listRow(l: YtList) = EntityItem(
        l.title,
        Format.join(if (filter == 3) (if (l.count in 1..3) "סינגל" else "אלבום") else "פלייליסט", l.artist, if (l.count > 0) "${l.count} פריטים" else ""),
        l.artist.ifBlank { l.title } + l.title,
        if (filter == 3) R.drawable.ic_album else R.drawable.ic_queue_music,
        onClick = { act.openAlbum(l) },
        onMore = { act.listActions(l, null) },
    )

    private fun podcastRow(p: PodcastResult) = EntityItem(
        p.name,
        Format.join(p.author, if (p.episodeCount > 0) "${p.episodeCount} פרקים" else ""),
        p.name,
        R.drawable.ic_podcasts,
        onClick = { act.openPodcast(p.feedUrl, p.name) },
    )
}

// ====================================================================== Library

class LibraryScreen(act: MainActivity) : ListScreen(act) {
    override val title = "הספרייה שלך"
    override val topIcon = R.drawable.ic_add
    override val topLabel = "פלייליסט חדש"
    override fun onTopAction() = newPlaylist()

    private var filter = 0

    override fun onBack(): Boolean {
        if (filter == 0) return false
        filter = 0
        render()
        list.scrollToPosition(0)
        return true
    }

    override fun render() {
        val items = ArrayList<Item>()
        items += ChipsItem(listOf("הכול", "פלייליסטים", "אלבומים", "אמנים", "הסכתים", "הורדות"), filter) { filter = it; render() }
        val all = filter == 0
        var shown = 0

        if (all || filter == 1) {
            val liked = Store.favorites()
            items += EntityItem("אהבתי", Format.join("פלייליסט", Format.count(liked.size, "פריט", "פריטים")), "אהבתי",
                R.drawable.ic_favorite_fill, brand = true, onClick = { act.push(CollectionScreen(act, CollectionScreen.LIKED)) })
            shown++
        }
        if (all) {
            items += EntityItem("הושמע לאחרונה", "כל מה ששמעת", "היסטוריה", R.drawable.ic_history,
                onClick = { act.push(CollectionScreen(act, CollectionScreen.HISTORY)) })
        }
        if (all || filter == 5) {
            items += EntityItem("שמור בטלפון", Format.count(Store.downloads().size, "קובץ", "קבצים"), "הורדות", R.drawable.ic_download_done,
                onClick = { act.push(CollectionScreen(act, CollectionScreen.DOWNLOADS)) })
            shown++
        }
        if (all || filter == 1) {
            Store.folders().forEach { f ->
                items += EntityItem(
                    f.name, Format.join("פלייליסט", Format.count(f.tracks.size, "פריט", "פריטים")), f.name,
                    R.drawable.ic_queue_music,
                    onClick = { act.push(CollectionScreen(act, CollectionScreen.PLAYLIST, f.id)) },
                    onMore = { playlistActions(act, f) },
                )
                shown++
            }
            if (filter == 1) items += ButtonsItem(listOf("+ פלייליסט חדש" to { newPlaylist() }))
        }
        if (all || filter == 2) {
            Store.savedAlbums().forEach { l ->
                items += EntityItem(l.title, Format.join("אלבום", l.artist), l.artist.ifBlank { l.title } + l.title,
                    onClick = { act.openAlbum(l) }, onMore = { act.listActions(l, null) })
                shown++
            }
        }
        if (all || filter == 3) {
            Store.followedArtists().forEach { a ->
                items += EntityItem(a.name, "אמן", a.name, round = true, onClick = { act.openArtist(a) })
                shown++
            }
        }
        if (all || filter == 4) {
            Store.subscriptions().forEach { s ->
                items += EntityItem(s.title, Format.join("הסכת", s.author), s.title, R.drawable.ic_podcasts, onClick = { act.openPodcast(s.feedUrl, s.title) })
                shown++
            }
        }
        if (filter in 2..4 && shown == 0) {
            items += MessageItem(
                when (filter) {
                    2 -> "אלבומים ששמרת יופיעו כאן. בעמוד של אלבום לחץ \"שמור בספרייה\"."
                    3 -> "אמנים שאתה עוקב אחריהם יופיעו כאן. בעמוד של אמן לחץ \"עקוב\"."
                    else -> "הסכתים שאתה עוקב אחריהם יופיעו כאן."
                }
            )
        }
        adapter.items = items
    }

    private fun newPlaylist() {
        act.askText("פלייליסט חדש", "", "יצירה") { name ->
            val f = Store.createFolder(name)
            act.push(CollectionScreen(act, CollectionScreen.PLAYLIST, f.id))
        }
    }

    companion object {
        fun playlistActions(act: MainActivity, f: Folder, onDeleted: () -> Unit = {}) {
            val ctx = PlayContext(PlayContext.PLAYLIST, f.name, "פלייליסט", f.id, Kind.MUSIC)
            act.sheet(
                f.name, Format.count(f.tracks.size, "פריט", "פריטים"),
                listOf(
                    SheetAction(R.drawable.ic_play_arrow, "נגן הכול") { act.playAll(f.tracks, ctx) },
                    SheetAction(R.drawable.ic_shuffle, "ערבב") { act.shuffle(f.tracks, ctx) },
                    SheetAction(R.drawable.ic_queue_music, "הוסף הכול לתור") { act.addAllToQueue(f.tracks) },
                    SheetAction(R.drawable.ic_download, "שמור את כל הפלייליסט בטלפון") { act.downloadAll(f.tracks) },
                    SheetAction(R.drawable.ic_edit, "שנה שם") {
                        act.askText("שם הפלייליסט", f.name, "שמירה") { Store.renameFolder(f.id, it) }
                    },
                    SheetAction(R.drawable.ic_delete, "מחק את הפלייליסט") {
                        act.confirm("למחוק את \"${f.name}\"?", "השירים והפרקים עצמם לא יימחקו, רק הפלייליסט.", "מחיקה") {
                            Store.deleteFolder(f.id)
                            onDeleted()
                        }
                    },
                ),
            )
        }
    }
}

// ====================================================================== Settings

class SettingsScreen(act: MainActivity) : ListScreen(act) {
    override val title = "הגדרות"

    override fun render() {
        val items = ArrayList<Item>()
        items += HeaderItem("מראה")
        items += EntityItem("ערכת צבעים", Settings.themeLabel(act), "צבע", R.drawable.ic_dark_mode, onClick = { chooseTheme() })
        items += EntityItem("גודל תצוגה", Settings.scaleLabel(act), "גודל", R.drawable.ic_format_size, onClick = { chooseScale() })
        val c = act.resources.configuration
        val m = act.resources.displayMetrics
        items += MessageItem(
            "המסך שלך: ${m.widthPixels}×${m.heightPixels} פיקסלים, רוחב ${c.screenWidthDp} יחידות.\n" +
                "במצב אוטומטי הגודל מותאם לבד למסך. אם משהו נראה גדול או קטן מדי, בחר גודל ידני."
        )

        items += HeaderItem("השמעה")
        items += EntityItem(
            "המשך אוטומטי עם המלצות",
            if (Store.autoplay()) "מופעל: בסוף שיר או שמע מיוטיוב יתנגן משהו דומה" else "כבוי",
            "המשך", R.drawable.ic_autoplay,
            onClick = { Store.setAutoplay(!Store.autoplay()) },
        )

        items += HeaderItem("קישורים מיוטיוב וספוטיפיי")
        items += EntityItem(
            "פתיחת קישורים ב-JUBAL",
            "איך לשמוע קישורים מיוטיוב וספוטיפיי כאן, בשמע בלבד",
            "קישור", R.drawable.ic_link,
            onClick = { linksHelp() },
        )

        items += HeaderItem("עדכונים")
        items += EntityItem(
            "בדיקת עדכונים",
            "JUBAL בודק לבד כל יום, כדי שהשמע מיוטיוב ימשיך לעבוד גם אחרי שינויים ביוטיוב",
            "עדכון", R.drawable.ic_download,
            onClick = { act.checkForUpdate(force = true, quietIfNone = false) },
        )

        items += HeaderItem("המלצות ופרטיות")
        items += EntityItem("איך עובדות ההמלצות", "כמו ביוטיוב ובספוטיפיי, רק ממה שבחרת", "המלצות", R.drawable.ic_info,
            onClick = { act.showText("איך עובדות ההמלצות", RECS_HELP) })
        items += EntityItem("איפוס ההמלצות", "מתחיל ללמוד מחדש. אהבתי, פלייליסטים ומעקבים נשארים", "איפוס", R.drawable.ic_delete,
            onClick = {
                act.confirm("לאפס את ההמלצות?", "כל מה שהמערכת למדה על הטעם שלך יימחק.", "איפוס") {
                    Store.resetRecommendations()
                    Store.clearCache("albums")
                    Store.clearCache("pods")
                    act.toast("ההמלצות אופסו")
                }
            })
        items += EntityItem("ניקוי היסטוריית ההאזנה", "", "ניקוי", R.drawable.ic_history,
            onClick = { act.confirm("לנקות את ההיסטוריה?", "גם \"המשך האזנה\" יתרוקן.", "ניקוי") { Store.clearHistory() } })
        items += EntityItem("ניקוי חיפושים אחרונים", "", "חיפוש", R.drawable.ic_search,
            onClick = { Store.clearSearches(); act.toast("נוקה") })

        val version = runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull() ?: ""
        items += LogoItem("גרסה $version · בלי תמונות, בלי וידאו ובלי דפדפן")
        adapter.items = items
    }

    private fun linksHelp() {
        act.sheet(
            "פתיחת קישורים ב-JUBAL",
            "ביוטיוב או בספוטיפיי: \"שיתוף\" ← JUBAL",
            listOf(
                SheetAction(R.drawable.ic_info, "איך זה עובד") { act.showText("פתיחת קישורים", LINKS_HELP) },
                SheetAction(R.drawable.ic_settings, "פתח את הגדרות הקישורים של JUBAL") { openLinkSettings() },
            ),
        )
    }

    private fun openLinkSettings() {
        val pkg = Uri.parse("package:${act.packageName}")
        val intent = if (Build.VERSION.SDK_INT >= 31) {
            Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        try {
            act.startActivity(intent)
        } catch (e: Exception) {
            act.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    private fun chooseTheme() {
        val current = Settings.THEMES.indexOfFirst { it.first == Settings.theme(act) }.coerceAtLeast(0)
        act.choose("ערכת צבעים", Settings.THEMES.map { it.second }, current) { i ->
            Settings.setTheme(act, Settings.THEMES[i].first)
        }
    }

    private fun chooseScale() {
        val s = Settings.scale(act)
        val current = Settings.SCALES.indexOfFirst { kotlin.math.abs(it.first - s) < 0.001f }.coerceAtLeast(0)
        act.choose("גודל תצוגה", Settings.SCALES.map { it.second }, current) { i ->
            Settings.setScale(act, Settings.SCALES[i].first)
            act.recreate()
        }
    }

    companion object {
        const val RECS_HELP =
            "ההמלצות נבנות רק בטלפון שלך, ברוח יוטיוב וספוטיפיי:\n\n" +
                "• מה שנחשב: שיר, שמע מיוטיוב או פרק שבחרת לשמוע בעצמך, האזנה של יותר מ-30 שניות, האזנה עד הסוף, \"אהבתי\", הוספה לפלייליסט, שמירה בטלפון ומעקב אחרי אמן או הסכת.\n" +
                "• מה שלא נחשב: מה שמתנגן לבד אחרי משהו אחר, כחלק מתור, אלבום, פלייליסט או השמעה אוטומטית.\n" +
                "• דילוג בתוך 30 שניות נחשב כ\"פחות מתאים\", גם לשיר וגם לאמן.\n" +
                "• ההצעות עצמן מגיעות מ\"הבא בתור\" של יוטיוב לשירים שבחרת (מי ששומע את זה שומע גם את זה). שיר שמומלץ מכמה שירים שאהבת עולה למעלה, ואותות ישנים נחלשים עם הזמן.\n" +
                "• בערך אחת לשלוש הצעות היא מאמן שעוד לא שמעת, כדי לגלות דברים חדשים.\n" +
                "• הסכתים מומלצים לפי הקטגוריות של מה שאתה עוקב אחריו ושומע, כמו ב-Apple Podcasts ובספוטיפיי."

        const val LINKS_HELP =
            "הדרך הכי פשוטה:\n" +
                "1. ביוטיוב, ביוטיוב מיוזיק או בספוטיפיי, לחץ \"שיתוף\".\n" +
                "2. בחר JUBAL מהרשימה.\n" +
                "3. השמע יתחיל לבד, בלי תמונה ובלי וידאו.\n\n" +
                "כדי שקישורים ייפתחו ישר ב-JUBAL (למשל מוואטסאפ):\n" +
                "1. לחץ למטה על \"פתח את הגדרות הקישורים של JUBAL\".\n" +
                "2. הפעל \"פתיחת קישורים נתמכים\", ולחץ \"הוספת קישור\" וסמן את כולם.\n" +
                "3. אם יוטיוב מותקן, ייתכן שתצטרך לכבות את אותה הגדרה ביישומון יוטיוב.\n\n" +
                "בקישור של ספוטיפיי, JUBAL מחפש את אותו שיר ביוטיוב מיוזיק, ואת אותו פרק במאגר ההסכתים. ספוטיפיי לא מאפשר ליישומונים אחרים להשמיע את הקבצים שלו."
    }
}
