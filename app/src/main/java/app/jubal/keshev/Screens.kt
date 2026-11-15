package app.jubal.keshev

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One page of the app. Its view is built once and kept, so going back keeps your place. */
abstract class Screen(protected val act: MainActivity) {
    abstract val title: String
    private var root: View? = null
    fun view(): View = root ?: build().also { root = it }
    protected abstract fun build(): View
    open fun onShown() {}
    open fun onHidden() {}
}

// ---------------------------------------------------------------- Search

class SearchScreen(act: MainActivity) : Screen(act) {
    override val title = "חיפוש"
    private var youtube = false
    private val adapter = RowAdapter()
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var podcastsButton: Button
    private lateinit var youtubeButton: Button
    private var ytSearch: YtSearch? = null
    private val ytResults = ArrayList<YtVideo>()
    private var loadingMore = false

    override fun build(): View {
        val c = act
        val root = c.vbox()

        val searchRow = c.hbox()
        input = EditText(c).apply {
            hint = "שם הפודקאסט או נושא"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    search()
                    true
                } else {
                    false
                }
            }
            withoutWebActions()
        }
        searchRow.addWeighted(input)
        searchRow.addView(c.button("חפש") { search() })
        root.addPadded(searchRow, verticalDp = 8)

        val sourceRow = c.hbox()
        podcastsButton = c.button("פודקאסטים") { setSource(false) }
        youtubeButton = c.button("יוטיוב (שמע)") { setSource(true) }
        sourceRow.addWeighted(podcastsButton)
        sourceRow.addWeighted(youtubeButton)
        root.addPadded(sourceRow, verticalDp = 0)

        status = c.label("חפש פודקאסט לפי שם, או עבור ליוטיוב כדי לשמוע הרצאות ופרקים משם.", 14f, secondary = true)
        root.addPadded(status, verticalDp = 8)

        root.addFill(c.list(adapter))
        setSource(false)
        return root
    }

    private fun setSource(toYoutube: Boolean) {
        youtube = toYoutube
        podcastsButton.alpha = if (toYoutube) 0.55f else 1f
        youtubeButton.alpha = if (toYoutube) 1f else 0.55f
        podcastsButton.isSelected = !toYoutube
        youtubeButton.isSelected = toYoutube
    }

    private fun hideKeyboard() {
        val imm = act.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun search() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()

        if (query.startsWith("http://") || query.startsWith("https://")) {
            if (query.contains("youtube.com/") || query.contains("youtu.be/")) {
                act.play(PlayRef("yt:$query", "יוטיוב", "", "yt", query))
            } else {
                act.push(PodcastScreen(act, query, "פודקאסט"))
            }
            return
        }

        status.text = "מחפש…"
        adapter.rows = emptyList()
        val useYoutube = youtube
        act.lifecycleScope.launch {
            try {
                if (useYoutube) {
                    val s = YtSearch(query)
                    val found = withContext(Dispatchers.IO) { s.first() }
                    ytSearch = s
                    ytResults.clear()
                    ytResults += found
                    status.text = if (found.isEmpty()) "לא נמצאו תוצאות ביוטיוב." else "תוצאות מיוטיוב (שמע בלבד):"
                    renderYoutube()
                } else {
                    val found = withContext(Dispatchers.IO) { Podcasts.search(query) }
                    status.text = if (found.isEmpty()) {
                        "לא נמצאו פודקאסטים. נסה שם אחר, או חפש ביוטיוב."
                    } else {
                        "נמצאו ${found.size} פודקאסטים:"
                    }
                    adapter.rows = found.map { p ->
                        Row(p.name, p.author, if (p.episodeCount > 0) "${p.episodeCount} פרקים" else "") {
                            act.push(PodcastScreen(act, p.feedUrl, p.name))
                        }
                    }
                }
            } catch (e: Exception) {
                status.text = "החיפוש נכשל. בדוק את החיבור לאינטרנט ונסה שוב."
            }
        }
    }

    private fun renderYoutube() {
        val rows = ytResults.map { v ->
            Row(v.title, v.channel, Format.duration(v.durationSec)) { youtubeActions(v) }
        }
        val more = if (ytSearch?.hasMore == true) listOf(Row("עוד תוצאות…") { loadMore() }) else emptyList()
        adapter.rows = rows + more
    }

    private fun loadMore() {
        val s = ytSearch ?: return
        if (loadingMore) return
        loadingMore = true
        act.lifecycleScope.launch {
            try {
                val more = withContext(Dispatchers.IO) { s.more() }
                if (s === ytSearch) {
                    ytResults += more
                    renderYoutube()
                }
            } catch (e: Exception) {
                act.toast("לא הצלחתי לטעון עוד תוצאות")
            } finally {
                loadingMore = false
            }
        }
    }

    private fun youtubeActions(v: YtVideo) {
        val ref = PlayRef("yt:${v.url}", v.title, v.channel, "yt", v.url)
        act.choose(
            v.title,
            listOf(
                "נגן" to { act.play(ref) },
                "שמור בטלפון" to { act.download(ref) },
            ),
        )
    }
}

// ---------------------------------------------------------------- Podcast page

class PodcastScreen(act: MainActivity, private val feedUrl: String, private var name: String) : Screen(act) {
    override val title: String get() = name
    private val adapter = RowAdapter()
    private lateinit var header: TextView
    private lateinit var author: TextView
    private lateinit var status: TextView
    private lateinit var subscribeButton: Button
    private var feed: Feed? = null
    private var loading = false

    override fun build(): View {
        val c = act
        val root = c.vbox()
        header = c.label(name, 18f, bold = true)
        root.addPadded(header, verticalDp = 8)
        author = c.label("", 14f, secondary = true)
        root.addPadded(author, verticalDp = 0)

        val buttons = c.hbox()
        subscribeButton = c.button("") { toggleSubscription() }
        buttons.addWeighted(subscribeButton)
        buttons.addWeighted(c.button("על הפודקאסט") {
            act.showText(name, Podcasts.plainText(feed?.rawDescription ?: ""))
        })
        root.addPadded(buttons, verticalDp = 4)

        status = c.label("", 14f, secondary = true)
        root.addPadded(status, verticalDp = 4)
        root.addFill(c.list(adapter))
        updateSubscribeButton()
        return root
    }

    override fun onShown() {
        updateSubscribeButton()
        if (feed == null) load() else render()
    }

    private fun load() {
        if (loading) return
        loading = true
        status.text = "טוען פרקים…"
        act.lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Podcasts.loadFeed(feedUrl) }
                feed = f
                name = f.title
                header.text = f.title
                author.text = f.author
                author.visibility = if (f.author.isBlank()) View.GONE else View.VISIBLE
                act.refreshTitle(this@PodcastScreen)
                render()
            } catch (e: Exception) {
                status.text = "לא הצלחתי לטעון את הפודקאסט. בדוק את החיבור ונסה שוב."
            } finally {
                loading = false
            }
        }
    }

    private fun render() {
        val f = feed ?: return
        status.text = if (f.episodes.isEmpty()) "אין פרקים להשמעה." else "${f.episodes.size} פרקים"
        adapter.rows = f.episodes.map { e ->
            Row(e.title, Format.join(Format.date(e.date), Format.duration(e.durationSec)), stateOf(e)) {
                episodeActions(f, e)
            }
        }
    }

    private fun stateOf(e: Episode): String {
        val id = e.audioUrl
        return when {
            Store.savedFileFor(id) != null -> "שמור בטלפון"
            Store.isDone(id) -> "נשמע"
            Store.position(id) > 0 -> "המשך מ-" + Format.time(Store.position(id))
            else -> ""
        }
    }

    private fun episodeActions(f: Feed, e: Episode) {
        val ref = PlayRef(e.audioUrl, e.title, f.title, "url", e.audioUrl)
        val resume = Store.position(e.audioUrl)
        val options = ArrayList<Pair<String, () -> Unit>>()
        options += (if (resume > 0) "המשך מ-" + Format.time(resume) else "נגן") to { act.play(ref) }
        if (resume > 0) {
            options += "נגן מההתחלה" to {
                Store.savePosition(e.audioUrl, 0)
                act.play(ref)
            }
        }
        if (e.isVideo) {
            options += "פרק וידאו: אפשר רק להאזין לו" to { act.play(ref) }
        } else if (Store.savedFileFor(e.audioUrl) == null) {
            options += "שמור בטלפון" to { act.download(ref) }
        }
        options += "תיאור הפרק" to { act.showText(e.title, Podcasts.plainText(e.rawDescription)) }
        act.choose(e.title, options)
    }

    private fun toggleSubscription() {
        if (Store.isSubscribed(feedUrl)) {
            Store.unsubscribe(feedUrl)
            act.toast("הוסר מהספרייה")
        } else {
            Store.subscribe(Subscription(name, feed?.author ?: "", feedUrl))
            act.toast("נוסף לספרייה")
        }
        updateSubscribeButton()
    }

    private fun updateSubscribeButton() {
        if (!::subscribeButton.isInitialized) return
        subscribeButton.text = if (Store.isSubscribed(feedUrl)) "הסר מהספרייה" else "הוסף לספרייה"
    }
}

// ---------------------------------------------------------------- Library

class LibraryScreen(act: MainActivity) : Screen(act) {
    override val title = "הספרייה שלי"
    private val adapter = RowAdapter()
    private lateinit var status: TextView
    private val onChange: () -> Unit = { render() }

    override fun build(): View {
        val root = act.vbox()
        status = act.label("", 14f, secondary = true)
        root.addPadded(status, verticalDp = 8)
        root.addFill(act.list(adapter))
        return root
    }

    override fun onShown() {
        Store.addListener(onChange)
        render()
    }

    override fun onHidden() {
        Store.removeListener(onChange)
    }

    private fun render() {
        val rows = ArrayList<Row>()
        Store.last()?.let { last ->
            rows += Row("המשך האזנה: ${last.title}", last.artist) { act.play(last) }
        }
        val subs = Store.subscriptions()
        subs.forEach { s ->
            rows += Row(s.title, s.author) { act.push(PodcastScreen(act, s.feedUrl, s.title)) }
        }
        status.text = if (subs.isEmpty()) {
            "עדיין אין כאן פודקאסטים. חפש פודקאסט, היכנס אליו ולחץ \"הוסף לספרייה\"."
        } else {
            "הפודקאסטים שלך:"
        }
        adapter.rows = rows
    }
}

// ---------------------------------------------------------------- Saved files

class DownloadsScreen(act: MainActivity) : Screen(act) {
    override val title = "שמור בטלפון"
    private val adapter = RowAdapter()
    private lateinit var status: TextView
    private val onChange: () -> Unit = { render() }

    override fun build(): View {
        val root = act.vbox()
        status = act.label("", 14f, secondary = true)
        root.addPadded(status, verticalDp = 8)
        root.addFill(act.list(adapter))
        return root
    }

    override fun onShown() {
        Store.addListener(onChange)
        render()
    }

    override fun onHidden() {
        Store.removeListener(onChange)
    }

    private fun render() {
        val files = Store.savedFiles()
        status.text = if (files.isEmpty()) {
            "עדיין לא שמרת פרקים. בכל פרק אפשר לבחור \"שמור בטלפון\"."
        } else {
            "הקבצים נמצאים בזיכרון הטלפון, בתיקייה Podcasts/Keshev."
        }
        adapter.rows = files.map { f ->
            Row(f.title, Format.join(f.artist, Format.date(f.time))) { fileActions(f) }
        }
    }

    private fun fileActions(f: SavedFile) {
        act.choose(
            f.title,
            listOf(
                "נגן" to { act.play(PlayRef(f.mediaId, f.title, f.artist, "url", f.uri)) },
                "מחק מהטלפון" to { delete(f) },
            ),
        )
    }

    private fun delete(f: SavedFile) {
        try {
            val uri = Uri.parse(f.uri)
            if (uri.scheme == "content") act.contentResolver.delete(uri, null, null)
            else uri.path?.let { File(it).delete() }
        } catch (e: Exception) {
            act.toast("את הקובץ עצמו אפשר למחוק מתיקיית Podcasts/Keshev")
        }
        Store.removeSavedFile(f.mediaId)
    }
}

// ---------------------------------------------------------------- Player

class PlayerScreen(act: MainActivity) : Screen(act) {
    override val title = "מתנגן עכשיו"
    private lateinit var episodeTitle: TextView
    private lateinit var episodeArtist: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playButton: Button
    private lateinit var speedButton: Button
    private var touching = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 500)
        }
    }
    private val watcher: () -> Unit = { update() }

    override fun build(): View {
        val c = act
        val root = c.vbox()
        episodeTitle = c.label("", 19f, bold = true)
        root.addPadded(episodeTitle, verticalDp = 16)
        episodeArtist = c.label("", 15f, secondary = true)
        root.addPadded(episodeArtist, verticalDp = 0)

        seek = SeekBar(c).apply {
            max = 1000
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !touching) seekToFraction(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { touching = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    touching = false
                    seekToFraction(bar.progress)
                }
            })
        }
        root.addPadded(seek, verticalDp = 16)

        timeText = c.label("", 14f, secondary = true).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addPadded(timeText, verticalDp = 0)

        val controls = c.hbox().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        controls.addWeighted(c.button("-15 שנ׳") { act.controller?.seekBack() })
        playButton = c.button("נגן") { act.togglePlay() }
        controls.addWeighted(playButton, 1.3f)
        controls.addWeighted(c.button("+30 שנ׳") { act.controller?.seekForward() })
        root.addPadded(controls, verticalDp = 16)

        val more = c.hbox()
        speedButton = c.button("מהירות 1x") { nextSpeed() }
        more.addWeighted(speedButton)
        more.addWeighted(c.button("עצור") {
            act.controller?.let {
                it.pause()
                it.stop()
                it.clearMediaItems()
            }
            update()
        })
        root.addPadded(more, verticalDp = 4)

        val hint = c.label("ההשמעה ממשיכה גם כשהיישומון סגור. שליטה גם מההתראה למעלה.", 13f, secondary = true)
        root.addPadded(hint, verticalDp = 16)
        return root
    }

    override fun onShown() {
        act.watchPlayer(watcher)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onHidden() {
        act.unwatchPlayer(watcher)
        handler.removeCallbacks(ticker)
    }

    private fun seekToFraction(progress: Int) {
        val c = act.controller ?: return
        val duration = c.duration
        if (duration > 0) c.seekTo(duration * progress / 1000)
    }

    private fun nextSpeed() {
        val c = act.controller ?: return
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
        val current = c.playbackParameters.speed
        val index = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        c.setPlaybackSpeed(speeds[(index + 1) % speeds.size])
        update()
    }

    private fun update() {
        if (!::playButton.isInitialized) return
        val c = act.controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            episodeTitle.text = "לא מתנגן כלום כרגע"
            episodeArtist.text = ""
            timeText.text = ""
            seek.progress = 0
            playButton.text = "נגן"
            return
        }
        episodeTitle.text = item.mediaMetadata.title ?: ""
        episodeArtist.text = item.mediaMetadata.artist ?: ""
        val position = c.currentPosition
        val duration = c.duration
        val loading = c.playbackState == Player.STATE_BUFFERING
        timeText.text = when {
            loading && position <= 0 -> "טוען…"
            duration > 0 -> Format.time(position) + " / " + Format.time(duration)
            else -> Format.time(position)
        }
        if (!touching && duration > 0) seek.progress = ((position * 1000) / duration).toInt()
        playButton.text = if (c.playWhenReady) "השהה" else "נגן"
        val speed = c.playbackParameters.speed
        speedButton.text = "מהירות " + (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "x"
    }
}
