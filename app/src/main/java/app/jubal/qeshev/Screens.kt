package app.jubal.qeshev

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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
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

    abstract fun render()

    protected fun defaultSubtitle(t: Track): String = when (t.kind) {
        Kind.PODCAST -> Format.join(t.artist, Format.date(t.date), Format.duration(t.durationSec), status(t))
        else -> Format.join(t.artist, Format.duration(t.durationSec), status(t))
    }

    protected fun status(t: Track): String = when {
        Store.downloadFor(t.id) != null -> "שמור"
        t.kind != Kind.MUSIC && Store.isDone(t.id) -> "נשמע"
        else -> ""
    }

    /** A row that plays [t] (or the whole [all] list from [index]) and offers the usual actions. */
    protected fun row(
        t: Track,
        subtitle: String = defaultSubtitle(t),
        all: List<Track>? = null,
        index: Int = 0,
        extra: List<SheetAction> = emptyList(),
        description: String? = null,
    ): TrackItem = TrackItem(
        track = t,
        subtitle = subtitle,
        progress = if (t.kind == Kind.MUSIC) -1 else Store.progress(t.id, t.durationSec).takeIf { it in 1..999 } ?: -1,
        onClick = { if (all != null) act.playTracks(all, index) else act.playOne(t) },
        onMore = { act.trackActions(t, extra, description) },
    )
}

/** A list with a search box on top and optional filter chips. */
abstract class SearchListScreen(act: MainActivity) : ListScreen(act) {
    protected lateinit var input: EditText
    protected var query = ""
    protected var filter = 0
    protected open val filters: List<String> = emptyList()
    protected abstract val hint: String

    override fun build(): View {
        val inflater = LayoutInflater.from(act)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val box = inflater.inflate(R.layout.view_search, root, false)
        input = box.findViewById(R.id.search_input)
        input.hint = hint
        input.withoutWebActions()
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submit()
                true
            } else {
                false
            }
        }
        input.doAfterTextChanged { text ->
            if (text.isNullOrEmpty() && query.isNotEmpty()) {
                query = ""
                onCleared()
                render()
            }
        }
        root.addView(box)

        if (filters.isNotEmpty()) {
            val group = ChipGroup(act).apply {
                isSingleSelection = true
                isSelectionRequired = true
                setPadding(act.dp(14), 0, act.dp(14), act.dp(2))
            }
            filters.forEachIndexed { i, label ->
                val chip = inflater.inflate(R.layout.chip_filter, group, false) as Chip
                chip.id = View.generateViewId()
                chip.text = label
                group.addView(chip)
                if (i == filter) chip.isChecked = true
                chip.setOnClickListener {
                    if (filter != i) {
                        filter = i
                        if (query.isNotEmpty()) search(query, i) else render()
                    }
                }
            }
            root.addView(group)
        }

        list = act.recycler(adapter)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun submit() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        act.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        query = q
        search(q, filter)
    }

    protected abstract fun search(q: String, filter: Int)
    protected open fun onCleared() {}
}

// ====================================================================== Home

class HomeScreen(act: MainActivity) : ListScreen(act) {
    override val title = "Qeshev"
    override val topIcon = R.drawable.ic_settings
    override val topLabel = "הגדרות"
    override fun onTopAction() = act.push(SettingsScreen(act))

    private var loadingEpisodes = false

    override fun onShown() {
        super.onShown()
        loadEpisodes(false)
    }

    private fun loadEpisodes(force: Boolean) {
        if (loadingEpisodes || Store.subscriptions().isEmpty()) return
        loadingEpisodes = true
        act.lifecycleScope.launch {
            try {
                Recs.newEpisodes(force)
            } catch (e: Exception) {
                // shown as "nothing new" below
            }
            loadingEpisodes = false
            if (visible) render()
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        items += TitleItem(Format.greeting(), "מה נשמע היום?")

        val continuing = Store.inProgress(4)
        if (continuing.isNotEmpty()) {
            items += HeaderItem("המשך האזנה")
            continuing.forEach { t ->
                items += row(t, Format.join(t.artist, Format.remaining(Store.remainingMs(t.id, t.durationSec))))
            }
        }

        val recs = Recs.forYou(setOf(Kind.MUSIC, Kind.VIDEO), 8)
        if (recs.isNotEmpty()) {
            items += HeaderItem("מומלץ בשבילך")
            recs.forEach { r -> items += row(r.track, "כי שמעת: ${r.because}") }
        }

        val subs = Store.subscriptions()
        if (subs.isNotEmpty()) {
            items += HeaderItem("פרקים חדשים", "רענון") { loadEpisodes(true) }
            val eps = Recs.cachedEpisodes()
            when {
                eps == null -> items += LoadingItem
                eps.isEmpty() -> items += MessageItem("אין פרקים חדשים כרגע.")
                else -> eps.take(6).forEach { items += row(it) }
            }
        }

        val shown = (continuing.map { it.id } + recs.map { it.track.id }).toSet()
        val recent = Store.history().map { it.track }.filter { it.id !in shown }.take(5)
        if (recent.isNotEmpty()) {
            items += HeaderItem("שמעת לאחרונה", "הכול") { act.push(CollectionScreen(act, CollectionScreen.HISTORY)) }
            recent.forEach { items += row(it) }
        }

        if (continuing.isEmpty() && recs.isEmpty() && subs.isEmpty() && recent.isEmpty()) {
            items += MessageItem(
                "ברוך הבא ל-Qeshev.\n" +
                    "אפשר לשמוע כאן פודקאסטים, שיעורים והרצאות מיוטיוב, ומוזיקה. הכול בשמע בלבד, בלי תמונות ובלי סרטונים.\n" +
                    "אחרי כמה האזנות יופיעו כאן המלצות לפי מה ששמעת."
            )
            items += ButtonsItem(
                listOf(
                    "פודקאסטים" to { act.selectTab(1) },
                    "סרטונים" to { act.selectTab(2) },
                    "מוזיקה" to { act.selectTab(3) },
                )
            )
        }
        adapter.items = items
    }
}

// ====================================================================== Podcasts

class PodcastsScreen(act: MainActivity) : SearchListScreen(act) {
    override val title = "פודקאסטים"
    override val hint = "חיפוש פודקאסט"
    private var results: List<PodcastResult>? = null
    private var message = ""

    override fun onCleared() {
        results = null
        message = ""
    }

    override fun search(q: String, filter: Int) {
        if (q.startsWith("http://") || q.startsWith("https://")) {
            act.openPodcast(q, "פודקאסט")
            return
        }
        results = null
        message = "מחפש…"
        render()
        act.lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { Podcasts.search(q) }
                if (q != query) return@launch
                results = found
                message = if (found.isEmpty()) "לא נמצאו פודקאסטים. נסה שם אחר, או חפש בלשונית \"סרטונים\"." else ""
            } catch (e: Exception) {
                message = "החיפוש נכשל. בדוק את החיבור לאינטרנט ונסה שוב."
            }
            if (visible) render()
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        if (query.isNotEmpty()) {
            if (message.isNotEmpty()) items += MessageItem(message)
            results?.forEach { p ->
                items += EntityItem(
                    p.name,
                    Format.join(p.author, if (p.episodeCount > 0) "${p.episodeCount} פרקים" else ""),
                    p.name,
                    onClick = { act.openPodcast(p.feedUrl, p.name) },
                )
            }
        } else {
            val subs = Store.subscriptions()
            if (subs.isEmpty()) {
                items += MessageItem("חפש פודקאסט לפי שם, היכנס אליו ולחץ \"עקוב\". הוא יופיע כאן, והפרקים החדשים שלו יופיעו בבית.")
            } else {
                items += HeaderItem("הפודקאסטים שלך")
                subs.forEach { s ->
                    items += EntityItem(s.title, s.author, s.title, onClick = { act.openPodcast(s.feedUrl, s.title) })
                }
            }
            val inProgress = Store.inProgress(10).filter { it.kind == Kind.PODCAST }.take(4)
            if (inProgress.isNotEmpty()) {
                items += HeaderItem("המשך האזנה")
                inProgress.forEach { t ->
                    items += row(t, Format.join(t.artist, Format.remaining(Store.remainingMs(t.id, t.durationSec))))
                }
            }
        }
        adapter.items = items
    }
}

class PodcastScreen(act: MainActivity, private val feedUrl: String, private var name: String) : ListScreen(act) {
    override val title: String get() = name
    private var feed: Feed? = null
    private var error = false
    private var loading = false

    override fun onShown() {
        super.onShown()
        if (feed == null) load()
    }

    private fun load() {
        if (loading) return
        loading = true
        error = false
        render()
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
            if (visible) render()
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        val f = feed
        items += TitleItem(f?.title ?: name, f?.author ?: "")
        if (f == null) {
            items += if (error) MessageItem("לא הצלחתי לטעון את הפודקאסט. בדוק את החיבור ונסה שוב.") else LoadingItem
            if (error) items += ButtonsItem(listOf("נסה שוב" to { load() }))
            adapter.items = items
            return
        }
        val following = Store.isSubscribed(feedUrl)
        val tracks = f.episodes.map { it.toTrack(f.title, feedUrl) }
        items += ButtonsItem(
            listOf(
                (if (following) "מפסיק לעקוב" else "עקוב") to {
                    if (following) Store.unsubscribe(feedUrl) else Store.subscribe(PodcastRef(f.title, f.author, feedUrl))
                    act.toast(if (following) "הוסר מהפודקאסטים שלך" else "נוסף לפודקאסטים שלך")
                },
                "על הפודקאסט" to { act.showText(f.title, Podcasts.plainText(f.rawDescription)) },
                "הוסף הכול לתיקייה" to { act.chooseFolder(tracks) },
            )
        )
        items += HeaderItem(if (tracks.isEmpty()) "אין פרקים" else "${tracks.size} פרקים")
        f.episodes.forEachIndexed { i, e ->
            items += row(tracks[i], description = Podcasts.plainText(e.rawDescription))
        }
        adapter.items = items
    }
}

// ====================================================================== YouTube: videos and music

abstract class YtSearchScreen(act: MainActivity, private val kind: Kind) : SearchListScreen(act) {
    protected abstract val filterCodes: List<String>
    private var search: YtSearch? = null
    private val results = ArrayList<YtResult>()
    private var message = ""
    private var loadingMore = false

    override fun onCleared() {
        search = null
        results.clear()
        message = ""
    }

    override fun search(q: String, filter: Int) {
        if (q.startsWith("http://") || q.startsWith("https://")) {
            openLink(q)
            return
        }
        val s = YtSearch(q, filterCodes[filter], kind)
        search = s
        results.clear()
        message = "מחפש…"
        render()
        act.lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { s.first() }
                if (search !== s) return@launch
                results += found
                message = if (found.isEmpty()) "לא נמצאו תוצאות." else ""
            } catch (e: Exception) {
                if (search === s) message = "החיפוש נכשל. נסה שוב בעוד רגע."
            }
            if (visible) render()
        }
    }

    private fun openLink(url: String) {
        val uri = android.net.Uri.parse(url)
        if (uri.getQueryParameter("list") != null && uri.getQueryParameter("v") == null) {
            act.push(YtListScreen(act, url, "רשימה", kind))
            return
        }
        val id = YouTube.videoId(url)
        if (id.isNullOrEmpty()) {
            act.toast("זה לא נראה כמו קישור של יוטיוב")
            return
        }
        act.playOne(Track.youtube(id, kind, "מיוטיוב", "", 0))
    }

    private fun more() {
        val s = search ?: return
        if (loadingMore) return
        loadingMore = true
        act.lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { s.more() }
                if (search === s) results += found
            } catch (e: Exception) {
                act.toast("לא הצלחתי לטעון עוד תוצאות")
            }
            loadingMore = false
            if (visible) render()
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        if (query.isNotEmpty()) {
            if (message.isNotEmpty()) items += MessageItem(message)
            for (r in results) {
                when (r) {
                    is YtTrackResult -> items += row(r.track)
                    is YtListResult -> {
                        val l = r.list
                        items += EntityItem(
                            l.title,
                            Format.join(l.artist, if (l.count > 0) "${l.count} פריטים" else ""),
                            l.artist.ifBlank { l.title },
                            onClick = { act.push(YtListScreen(act, l.url, l.title, l.kind)) },
                        )
                    }
                }
            }
            if (search?.hasMore == true && results.isNotEmpty()) {
                items += ButtonsItem(listOf((if (loadingMore) "טוען…" else "עוד תוצאות") to { more() }))
            }
        } else {
            idle(items)
        }
        adapter.items = items
    }

    protected open fun idle(items: ArrayList<Item>) {
        val recs = Recs.forYou(setOf(kind), 12)
        if (recs.isNotEmpty()) {
            items += HeaderItem("מומלץ בשבילך")
            items += ButtonsItem(listOf("נגן את כל ההמלצות" to { act.playTracks(recs.map { it.track }, 0) }))
            recs.forEach { r -> items += row(r.track, "כי שמעת: ${r.because}") }
        }
        val recent = Store.history().map { it.track }.filter { it.kind == kind }.take(8)
        if (recent.isNotEmpty()) {
            items += HeaderItem("שמעת לאחרונה")
            recent.forEach { items += row(it) }
        }
        if (recs.isEmpty() && recent.isEmpty()) items += MessageItem(emptyText)
    }

    protected abstract val emptyText: String
}

class VideosScreen(act: MainActivity) : YtSearchScreen(act, Kind.VIDEO) {
    override val title = "סרטונים"
    override val hint = "חיפוש ביוטיוב, שמע בלבד"
    override val filters = listOf("סרטונים", "פלייליסטים")
    override val filterCodes = listOf(YtSearch.VIDEOS, YtSearch.PLAYLISTS)
    override val emptyText =
        "כאן אפשר לשמוע שיעורים, הרצאות וכל סרטון מיוטיוב, בלי התמונה. אפשר גם להדביק קישור של יוטיוב בתיבת החיפוש."
}

class MusicScreen(act: MainActivity) : YtSearchScreen(act, Kind.MUSIC) {
    override val title = "מוזיקה"
    override val hint = "שירים, אמנים ואלבומים"
    override val filters = listOf("שירים", "אלבומים", "פלייליסטים")
    override val filterCodes = listOf(YtSearch.SONGS, YtSearch.ALBUMS, YtSearch.MUSIC_PLAYLISTS)
    override val emptyText =
        "חפש שיר או אמן. כשתשמע שיר, Qeshev ימשיך לבד עם שירים דומים (אפשר לכבות את זה בנגן)."

    override fun idle(items: ArrayList<Item>) {
        val favorites = Store.favorites().filter { it.kind == Kind.MUSIC }
        if (favorites.isNotEmpty()) {
            items += ButtonsItem(
                listOf(
                    "ערבב את האהובים" to { act.shuffle(favorites) },
                    "כל האהובים" to { act.push(CollectionScreen(act, CollectionScreen.FAVORITES)) },
                )
            )
        }
        super.idle(items)
    }
}

class YtListScreen(act: MainActivity, private val url: String, private var name: String, private val kind: Kind) : ListScreen(act) {
    override val title: String get() = name
    private var tracks: List<Track>? = null
    private var error = false

    override fun onShown() {
        super.onShown()
        if (tracks == null) load()
    }

    private fun load() {
        error = false
        render()
        act.lifecycleScope.launch {
            try {
                val (listName, loaded) = withContext(Dispatchers.IO) { YouTube.loadList(url, kind) }
                if (listName.isNotBlank()) name = listName
                tracks = loaded
                act.refreshTop(this@YtListScreen)
            } catch (e: Exception) {
                error = true
            }
            if (visible) render()
        }
    }

    override fun render() {
        val items = ArrayList<Item>()
        val t = tracks
        items += TitleItem(name, if (t != null) "${t.size} פריטים" else "")
        when {
            t == null && error -> {
                items += MessageItem("לא הצלחתי לפתוח את הרשימה.")
                items += ButtonsItem(listOf("נסה שוב" to { load() }))
            }
            t == null -> items += LoadingItem
            t.isEmpty() -> items += MessageItem("הרשימה ריקה.")
            else -> {
                items += ButtonsItem(
                    listOf(
                        "נגן הכול" to { act.playTracks(t, 0) },
                        "ערבב" to { act.shuffle(t) },
                        "שמור כתיקייה" to {
                            val folder = Store.createFolder(name, t)
                            act.toast("נשמר כתיקייה \"${folder.name}\"")
                        },
                    )
                )
                t.forEachIndexed { i, track -> items += row(track, all = t, index = i) }
            }
        }
        adapter.items = items
    }
}

// ====================================================================== Library

class LibraryScreen(act: MainActivity) : ListScreen(act) {
    override val title = "הספרייה"
    override val topIcon = R.drawable.ic_settings
    override val topLabel = "הגדרות"
    override fun onTopAction() = act.push(SettingsScreen(act))

    override fun render() {
        val items = ArrayList<Item>()
        val favorites = Store.favorites().size
        val downloads = Store.downloads().size
        items += EntityItem("אהובים", Format.count(favorites, "פריט", "פריטים"), "אהובים", R.drawable.ic_favorite_fill,
            onClick = { act.push(CollectionScreen(act, CollectionScreen.FAVORITES)) })
        items += EntityItem("שמור בטלפון", Format.count(downloads, "קובץ", "קבצים"), "שמור", R.drawable.ic_download_done,
            onClick = { act.push(CollectionScreen(act, CollectionScreen.DOWNLOADS)) })
        items += EntityItem("היסטוריה", "כל מה ששמעת לאחרונה", "היסטוריה", R.drawable.ic_history,
            onClick = { act.push(CollectionScreen(act, CollectionScreen.HISTORY)) })

        items += HeaderItem("תיקיות", "+ חדשה") { newFolder() }
        val folders = Store.folders()
        if (folders.isEmpty()) {
            items += MessageItem("צור תיקייה כדי לאסוף שיעורים, שירים ופרקים במקום אחד. מכל פריט אפשר לבחור \"הוסף לתיקייה\".")
        } else {
            folders.forEach { f ->
                items += EntityItem(
                    f.name, Format.count(f.tracks.size, "פריט", "פריטים"), f.name, R.drawable.ic_folder,
                    onClick = { act.push(CollectionScreen(act, CollectionScreen.FOLDER, f.id)) },
                    onMore = { folderActions(act, f) },
                )
            }
        }

        val subs = Store.subscriptions()
        if (subs.isNotEmpty()) {
            items += HeaderItem("פודקאסטים שאני עוקב אחריהם")
            subs.forEach { s ->
                items += EntityItem(s.title, s.author, s.title, onClick = { act.openPodcast(s.feedUrl, s.title) })
            }
        }
        adapter.items = items
    }

    private fun newFolder() {
        act.askText("תיקייה חדשה", "", "יצירה") { name ->
            val f = Store.createFolder(name)
            act.push(CollectionScreen(act, CollectionScreen.FOLDER, f.id))
        }
    }

    companion object {
        fun folderActions(act: MainActivity, f: Folder, onDeleted: () -> Unit = {}) {
            act.sheet(
                f.name, Format.count(f.tracks.size, "פריט", "פריטים"),
                listOf(
                    SheetAction(R.drawable.ic_play_arrow, "נגן הכול") { act.playTracks(f.tracks, 0) },
                    SheetAction(R.drawable.ic_shuffle, "ערבב") { act.shuffle(f.tracks) },
                    SheetAction(R.drawable.ic_queue_music, "הוסף הכול לתור") { act.addAllToQueue(f.tracks) },
                    SheetAction(R.drawable.ic_download, "שמור את כל התיקייה בטלפון") { act.downloadAll(f.tracks) },
                    SheetAction(R.drawable.ic_edit, "שנה שם") {
                        act.askText("שם התיקייה", f.name, "שמירה") { Store.renameFolder(f.id, it) }
                    },
                    SheetAction(R.drawable.ic_delete, "מחק את התיקייה") {
                        act.confirm("למחוק את \"${f.name}\"?", "הפריטים עצמם לא יימחקו, רק התיקייה.", "מחיקה") {
                            Store.deleteFolder(f.id)
                            onDeleted()
                        }
                    },
                ),
            )
        }
    }
}

class CollectionScreen(act: MainActivity, private val type: Int, private val folderId: String = "") : ListScreen(act) {
    override val title: String
        get() = when (type) {
            FAVORITES -> "אהובים"
            DOWNLOADS -> "שמור בטלפון"
            HISTORY -> "היסטוריה"
            else -> Store.folder(folderId)?.name ?: "תיקייה"
        }

    override val topIcon: Int
        get() = when (type) {
            FOLDER -> R.drawable.ic_more_vert
            HISTORY -> R.drawable.ic_delete
            else -> 0
        }

    override val topLabel: String get() = if (type == HISTORY) "נקה היסטוריה" else "אפשרויות"

    override fun onTopAction() {
        when (type) {
            FOLDER -> Store.folder(folderId)?.let { f -> LibraryScreen.folderActions(act, f) { act.goBack() } }
            HISTORY -> act.confirm("לנקות את ההיסטוריה?", "גם ההמלצות מבוססות על ההיסטוריה.", "ניקוי") { Store.clearHistory() }
        }
    }

    private fun tracks(): List<Track> = when (type) {
        FAVORITES -> Store.favorites()
        DOWNLOADS -> Store.downloads().map { it.track }
        HISTORY -> Store.history().map { it.track }
        else -> Store.folder(folderId)?.tracks ?: emptyList()
    }

    override fun render() {
        act.refreshTop(this)
        if (type == FOLDER && Store.folder(folderId) == null) {
            adapter.items = listOf(MessageItem("התיקייה נמחקה."))
            return
        }
        val t = tracks()
        val items = ArrayList<Item>()
        if (t.isEmpty()) {
            items += MessageItem(
                when (type) {
                    FAVORITES -> "עוד אין אהובים. בכל פריט, או בנגן, לחץ על הלב."
                    DOWNLOADS -> "עוד לא שמרת כלום. בכל פריט אפשר לבחור \"שמור בטלפון\", ואז הוא יעבוד גם בלי אינטרנט."
                    HISTORY -> "עוד לא שמעת כלום."
                    else -> "התיקייה ריקה. מכל פריט אפשר לבחור \"הוסף לתיקייה\"."
                }
            )
            adapter.items = items
            return
        }
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        buttons += "נגן הכול" to { act.playTracks(t, 0) }
        buttons += "ערבב" to { act.shuffle(t) }
        if (type == FOLDER || type == FAVORITES) buttons += "שמור הכול בטלפון" to { act.downloadAll(t) }
        items += ButtonsItem(buttons)
        if (type == DOWNLOADS) {
            items += MessageItem("הקבצים נמצאים בזיכרון הטלפון, בתיקיות Music/Qeshev ו-Podcasts/Qeshev.")
        }
        t.forEachIndexed { i, track ->
            val extra = when (type) {
                FOLDER -> listOf(SheetAction(R.drawable.ic_close, "הסר מהתיקייה") { Store.removeFromFolder(folderId, track.id) })
                HISTORY -> listOf(SheetAction(R.drawable.ic_close, "הסר מההיסטוריה") { Store.removeHistory(track.id) })
                else -> emptyList()
            }
            items += row(track, all = t, index = i, extra = extra)
        }
        adapter.items = items
    }

    companion object {
        const val FAVORITES = 0
        const val DOWNLOADS = 1
        const val HISTORY = 2
        const val FOLDER = 3
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
            if (Store.autoplay()) "מופעל: אחרי שיר או סרטון יתנגן משהו דומה" else "כבוי",
            "המשך", R.drawable.ic_autoplay,
            onClick = { Store.setAutoplay(!Store.autoplay()) },
        )

        items += HeaderItem("על Qeshev")
        val version = runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull() ?: ""
        items += MessageItem(
            "Qeshev $version\n" +
                "בלי תמונות, בלי וידאו ובלי דפדפן. פודקאסטים מהמאגר הפתוח של אפל, ושמע מיוטיוב דרך NewPipeExtractor."
        )
        adapter.items = items
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
}
