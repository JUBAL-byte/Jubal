package app.jubal.qeshev

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SheetAction(val icon: Int, val text: String, val run: () -> Unit)

class MainActivity : AppCompatActivity() {
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var backButton: ImageButton
    private lateinit var topAction: ImageButton
    private lateinit var nav: BottomNavigationView
    private lateinit var mini: View
    private lateinit var miniWave: WaveView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniProgress: LinearProgressIndicator
    private lateinit var splash: View
    private lateinit var playerPanel: PlayerPanel

    private val tabIds = intArrayOf(R.id.nav_home, R.id.nav_search, R.id.nav_library, R.id.nav_settings)
    private val stacks = List(4) { ArrayList<Screen>() }
    private var tab = 0

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set
    private val pendingActions = ArrayList<(MediaController) -> Unit>()
    private var lastItemId: String? = null
    private var splashShownAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateMiniProgress()
            if (playerPanel.isShown) playerPanel.update()
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = onPlayerChanged()
        override fun onPlayerError(error: PlaybackException) {
            handler.postDelayed({
                if (controller?.playerError != null) {
                    toast("ההשמעה נעצרה. לחץ נגן כדי לנסות שוב, או בדוק את החיבור לאינטרנט.")
                }
            }, 4_000)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val currentId: String? get() = controller?.currentMediaItem?.mediaId

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        titleView = findViewById(R.id.title)
        backButton = findViewById(R.id.back)
        topAction = findViewById(R.id.top_action)
        nav = findViewById(R.id.nav)
        mini = findViewById(R.id.mini)
        miniWave = findViewById(R.id.mini_wave)
        miniTitle = findViewById(R.id.mini_title)
        miniArtist = findViewById(R.id.mini_artist)
        miniPlay = findViewById(R.id.mini_play)
        miniProgress = findViewById(R.id.mini_progress)
        splash = findViewById(R.id.splash)
        playerPanel = PlayerPanel(this, findViewById(R.id.player_overlay))

        backButton.setOnClickListener { goBack() }
        topAction.setOnClickListener { stacks[tab].lastOrNull()?.onTopAction() }
        mini.setOnClickListener { playerPanel.show() }
        miniPlay.setOnClickListener { togglePlay() }

        val startTab = (savedInstanceState?.getInt(STATE_TAB) ?: 0).coerceIn(0, 3)
        showTab(startTab)
        nav.menu.findItem(tabIds[startTab])?.isChecked = true
        nav.setOnItemSelectedListener { item ->
            val i = tabIds.indexOf(item.itemId)
            if (i >= 0) showTab(i)
            true
        }
        nav.setOnItemReselectedListener { item ->
            val i = tabIds.indexOf(item.itemId)
            if (i >= 0) popToRoot(i)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        if (savedInstanceState == null) {
            showSplash()
            handleIntent(intent)
        } else if (savedInstanceState.getBoolean(STATE_PLAYER)) {
            playerPanel.show(animate = false)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null }
            if (c != null) {
                controller = c
                c.addListener(playerListener)
                val actions = pendingActions.toList()
                pendingActions.clear()
                actions.forEach { it(c) }
                onPlayerChanged()
            }
            hideSplash()
        }, ContextCompat.getMainExecutor(this))
        stacks[tab].lastOrNull()?.onShown()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        stacks[tab].lastOrNull()?.onHidden()
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        outState.putBoolean(STATE_PLAYER, playerPanel.isShown)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A hand-picked display size is applied when the screen is built, so rebuild it after a rotation.
        if (Settings.isCustomScale(this)) recreate()
    }

    // ------------------------------------------------------------------ opening screen

    private fun showSplash() {
        splash.visibility = View.VISIBLE
        splash.alpha = 1f
        splashShownAt = SystemClock.elapsedRealtime()
        // Never keep it up for long, even if the player is slow to connect.
        handler.postDelayed({ hideSplash() }, 2_500)
    }

    private fun hideSplash() {
        if (splash.visibility != View.VISIBLE || splashShownAt == 0L) return
        val wait = (900 - (SystemClock.elapsedRealtime() - splashShownAt)).coerceAtLeast(0)
        splashShownAt = 0L
        handler.postDelayed({
            splash.animate().alpha(0f).setDuration(260).withEndAction { splash.visibility = View.GONE }.start()
        }, wait)
    }

    // ------------------------------------------------------------------ links opened from other apps

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> Links.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            else -> null
        } ?: return
        openLink(url)
    }

    fun openLink(url: String) {
        if (!Links.isSupported(url)) {
            toast("JUBAL פותח קישורים של יוטיוב, יוטיוב מיוזיק, ספוטיפיי ואפל פודקאסטס")
            return
        }
        toast("פותח את הקישור…")
        lifecycleScope.launch {
            val target = try {
                withContext(Dispatchers.IO) { Links.resolve(url) }
            } catch (e: Exception) {
                null
            }
            when (target) {
                is Links.PlayTrack -> playOne(target.track)
                is Links.OpenList -> openAlbum(target.list)
                is Links.OpenArtist -> openArtist(target.artist)
                is Links.OpenPodcast -> openPodcast(target.feedUrl, target.name)
                is Links.Search -> {
                    selectTab(1)
                    (stacks[1].firstOrNull() as? SearchScreen)?.searchFor(target.query)
                }
                null -> toast("לא הצלחתי לפתוח את הקישור")
            }
        }
    }

    // ------------------------------------------------------------------ navigation

    private fun rootFor(index: Int): Screen = when (index) {
        0 -> HomeScreen(this)
        1 -> SearchScreen(this)
        2 -> LibraryScreen(this)
        else -> SettingsScreen(this)
    }

    fun push(screen: Screen) {
        if (playerPanel.isShown) playerPanel.hide()
        stacks[tab].lastOrNull()?.onHidden()
        stacks[tab].add(screen)
        display(screen)
    }

    fun goBack(): Boolean {
        if (playerPanel.isShown) {
            playerPanel.hide()
            return true
        }
        val stack = stacks[tab]
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1).onHidden()
            display(stack.last())
            return true
        }
        if (tab != 0) {
            selectTab(0)
            return true
        }
        return false
    }

    /** Switches tab the same way tapping the bottom bar does. */
    fun selectTab(index: Int) {
        if (tab == index && stacks[index].isNotEmpty()) return
        nav.selectedItemId = tabIds[index]
    }

    private fun showTab(index: Int) {
        if (playerPanel.isShown) playerPanel.hide()
        stacks[tab].lastOrNull()?.onHidden()
        tab = index
        val stack = stacks[index]
        if (stack.isEmpty()) stack.add(rootFor(index))
        display(stack.last())
    }

    private fun popToRoot(index: Int) {
        if (playerPanel.isShown) playerPanel.hide()
        val stack = stacks[index]
        if (stack.size <= 1) {
            if (stack.isEmpty()) showTab(index) else stack.first().onReselected()
            return
        }
        stack.last().onHidden()
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        display(stack.last())
    }

    private fun display(screen: Screen) {
        val v = screen.view()
        (v.parent as? ViewGroup)?.removeView(v)
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        backButton.visibility = if (stacks[tab].size > 1) View.VISIBLE else View.GONE
        refreshTop(screen)
        screen.onShown()
    }

    fun refreshTop(screen: Screen) {
        if (stacks[tab].lastOrNull() !== screen) return
        titleView.text = screen.title
        if (screen.topIcon != 0) {
            topAction.visibility = View.VISIBLE
            topAction.setImageResource(screen.topIcon)
            topAction.contentDescription = screen.topLabel
        } else {
            topAction.visibility = View.GONE
        }
    }

    fun openPodcast(feedUrl: String, name: String) = push(PodcastScreen(this, feedUrl, name))

    fun openPodcaster(author: String) {
        if (author.isBlank()) return
        push(PodcasterScreen(this, author))
    }

    fun openArtist(artist: ArtistRef) {
        if (artist.name.isBlank() && artist.url.isBlank()) return
        push(ArtistScreen(this, artist))
    }

    fun openAlbum(list: YtList) = push(AlbumScreen(this, list))

    /** Opens the page behind an entry in "recently played". */
    fun openContext(ctx: PlayContext) {
        when (ctx.type) {
            PlayContext.ALBUM, PlayContext.YT_LIST ->
                openAlbum(YtList(ctx.title, ctx.subtitle, ctx.ref, -1, ctx.kind, ctx.artistUrl))
            PlayContext.ARTIST -> openArtist(ArtistRef(ctx.title, ctx.ref))
            PlayContext.PLAYLIST -> push(CollectionScreen(this, CollectionScreen.PLAYLIST, ctx.ref))
            PlayContext.LIKED -> push(CollectionScreen(this, CollectionScreen.LIKED))
            PlayContext.PODCAST -> openPodcast(ctx.ref, ctx.title)
            else -> ctx.track?.let { playOne(it) }
        }
    }

    // ------------------------------------------------------------------ playback

    fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pendingActions += action
    }

    fun currentTrack(): Track? = Track.of(controller?.currentMediaItem)

    private fun onPlayerChanged() {
        val c = controller
        val item = c?.currentMediaItem
        val track = Track.of(item)
        if (c == null || item == null || track == null) {
            mini.visibility = View.GONE
        } else {
            mini.visibility = View.VISIBLE
            miniWave.playing = c.isPlaying
            miniTitle.text = track.title
            miniArtist.text = track.artist
            miniPlay.setImageResource(if (c.playWhenReady) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill)
            miniPlay.contentDescription = if (c.playWhenReady) "השהה" else "נגן"
            updateMiniProgress()
        }
        val id = item?.mediaId
        if (id != lastItemId) {
            lastItemId = id
            stacks[tab].lastOrNull()?.onPlayerItemChanged()
        }
        playerPanel.update()
    }

    private fun updateMiniProgress() {
        val c = controller ?: return
        val duration = c.duration
        if (duration > 0) {
            miniProgress.setProgressCompat(((c.currentPosition * 1000) / duration).toInt().coerceIn(0, 1000), false)
        } else {
            miniProgress.setProgressCompat(0, false)
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.playWhenReady && c.playerError == null) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /**
     * Plays a list from one item. [tapped] = the listener picked that exact item, which is what
     * recommendations learn from. "Play all" and "shuffle" set it to false: only the album,
     * playlist or artist is remembered, not each song that follows.
     */
    fun playTracks(tracks: List<Track>, index: Int, context: PlayContext? = null, tapped: Boolean = true) {
        if (tracks.isEmpty()) return
        val i = index.coerceIn(0, tracks.size - 1)
        val first = tracks[i]
        val start = if (first.kind == Kind.MUSIC) 0L else Store.position(first.id)
        val items = tracks.mapIndexed { n, t -> t.toMediaItem(active = tapped && n == i, context = context) }
        val ctx = context ?: if (first.kind == Kind.PODCAST && first.feedUrl.isNotBlank()) PlayContext.podcast(first) else PlayContext.song(first)
        if (context != null || tapped) Store.addContext(ctx)
        withController { c ->
            c.setMediaItems(items, i, start)
            c.prepare()
            c.play()
        }
        if (first.kind == Kind.PODCAST) playerPanel.show()
    }

    fun playOne(track: Track) = playTracks(listOf(track), 0)

    fun playAll(tracks: List<Track>, context: PlayContext?) = playTracks(tracks, 0, context, tapped = false)

    fun shuffle(tracks: List<Track>, context: PlayContext? = null) {
        if (tracks.isEmpty()) return
        playTracks(tracks.shuffled(), 0, context, tapped = false)
    }

    fun playNext(track: Track) {
        withController { c ->
            if (c.mediaItemCount == 0) {
                playOne(track)
            } else {
                c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem(active = false))
                toast("יתנגן אחרי הנוכחי")
            }
        }
    }

    fun addToQueue(track: Track) {
        withController { c ->
            if (c.mediaItemCount == 0) {
                playOne(track)
            } else {
                c.addMediaItem(track.toMediaItem(active = false))
                toast("נוסף לתור")
            }
        }
    }

    /** Adds to the end of the queue; with [quiet] no message is shown (used while loading albums one by one). */
    fun addAllToQueue(tracks: List<Track>, quiet: Boolean = false) {
        if (tracks.isEmpty()) return
        withController { c ->
            if (c.mediaItemCount == 0) {
                playAll(tracks, null)
            } else {
                c.addMediaItems(tracks.map { it.toMediaItem(active = false) })
                if (!quiet) toast("נוסף לתור")
            }
        }
    }

    fun toggleFavorite(track: Track) {
        val now = Store.toggleFavorite(track)
        toast(if (now) "נוסף ל\"אהבתי\"" else "הוסר מ\"אהבתי\"")
        playerPanel.update()
    }

    fun download(track: Track) {
        if (Store.downloadFor(track.id) != null) {
            toast("כבר שמור בטלפון")
            return
        }
        DownloadService.start(this, track)
        toast("ההורדה התחילה. אפשר לראות אותה בהתראות.")
    }

    fun downloadAll(tracks: List<Track>) {
        val missing = tracks.filter { Store.downloadFor(it.id) == null }
        if (missing.isEmpty()) {
            toast("הכול כבר שמור בטלפון")
            return
        }
        missing.forEach { DownloadService.start(this, it) }
        toast("מוריד ${missing.size} פריטים. אפשר לראות בהתראות.")
    }

    fun deleteDownload(track: Track) {
        val saved = Store.downloadFor(track.id) ?: return
        try {
            val uri = Uri.parse(saved.uri)
            if (uri.scheme == "content") contentResolver.delete(uri, null, null) else File(uri.path ?: "").delete()
            toast("נמחק מהטלפון")
        } catch (e: Exception) {
            toast("את הקובץ עצמו אפשר למחוק מתיקיית Qeshev בזיכרון הטלפון")
        }
        Store.removeDownload(track.id)
    }

    // ------------------------------------------------------------------ share, copy, details

    fun share(title: String, link: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$link")
        }
        try {
            startActivity(Intent.createChooser(send, "שיתוף"))
        } catch (e: Exception) {
            copyLink(link)
        }
    }

    fun copyLink(link: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("JUBAL", link))
        toast("הקישור הועתק")
    }

    private fun shareActions(title: String, link: String): List<SheetAction> = listOf(
        SheetAction(R.drawable.ic_share, "שיתוף הקישור") { share(title, link) },
        SheetAction(R.drawable.ic_content_copy, "העתקת הקישור") { copyLink(link) },
    )

    /** Title, artist, length and the full description, for songs and videos as well as episodes. */
    fun showDetails(track: Track, description: String? = null) {
        if (track.isYouTube) {
            toast("טוען פרטים…")
            lifecycleScope.launch {
                val d = try {
                    withContext(Dispatchers.IO) { YouTube.details(track) }
                } catch (e: Exception) {
                    null
                }
                val head = Format.join(
                    track.artist.ifBlank { d?.uploader ?: "" },
                    Format.duration(track.durationSec),
                    if ((d?.views ?: -1L) >= 0L) "${Format.big(d!!.views)} צפיות" else "",
                    d?.date ?: "",
                )
                val body = d?.description?.ifBlank { null } ?: "אין תיאור."
                showText(track.title, "$head\n\n$body")
            }
        } else {
            val head = Format.join(track.artist, track.author, Format.date(track.date), Format.duration(track.durationSec))
            showText(track.title, "$head\n\n${description?.ifBlank { null } ?: "אין תיאור."}")
        }
    }

    // ------------------------------------------------------------------ sheets and dialogs

    fun trackActions(track: Track, extra: List<SheetAction> = emptyList(), description: String? = null) {
        val actions = ArrayList<SheetAction>()
        actions += SheetAction(R.drawable.ic_play_arrow, "נגן עכשיו") { playOne(track) }
        actions += SheetAction(R.drawable.ic_playlist_play, "נגן אחרי הנוכחי") { playNext(track) }
        actions += SheetAction(R.drawable.ic_queue_music, "הוסף לתור") { addToQueue(track) }
        actions += if (Store.isFavorite(track.id)) {
            SheetAction(R.drawable.ic_favorite_fill, "הסר מ\"אהבתי\"") { toggleFavorite(track) }
        } else {
            SheetAction(R.drawable.ic_favorite, "הוסף ל\"אהבתי\"") { toggleFavorite(track) }
        }
        actions += SheetAction(R.drawable.ic_playlist_add, "הוסף לפלייליסט") { chooseFolder(listOf(track)) }
        actions += if (Store.downloadFor(track.id) != null) {
            SheetAction(R.drawable.ic_delete, "מחק מהטלפון") { deleteDownload(track) }
        } else {
            SheetAction(R.drawable.ic_download, "שמור בטלפון") { download(track) }
        }
        if (track.isYouTube && track.artist.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_person, "לדף של ${track.artist}") { openArtist(ArtistRef(track.artist, track.artistUrl)) }
        }
        if (track.kind == Kind.PODCAST && track.feedUrl.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_podcasts, "לעמוד ההסכת") { openPodcast(track.feedUrl, track.artist) }
        }
        if (track.kind == Kind.PODCAST && track.author.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_mic, "לדף של ${track.author}") { openPodcaster(track.author) }
        }
        if (track.kind == Kind.PODCAST && Store.position(track.id) > 0) {
            actions += SheetAction(R.drawable.ic_replay_10, "התחל מההתחלה") {
                Store.clearPosition(track.id)
                playOne(track)
            }
        }
        actions += SheetAction(R.drawable.ic_info, "פרטים ותיאור") { showDetails(track, description) }
        actions += shareActions(Format.join(track.title, track.artist), track.shareLink())
        actions += extra
        sheet(track.title, Format.join(track.artist, track.kind.label), actions)
    }

    fun listActions(list: YtList, tracks: List<Track>?) {
        val actions = ArrayList<SheetAction>()
        if (!tracks.isNullOrEmpty()) {
            actions += SheetAction(R.drawable.ic_queue_music, "הוסף הכול לתור") { addAllToQueue(tracks) }
            actions += SheetAction(R.drawable.ic_playlist_add, "הוסף לפלייליסט") { chooseFolder(tracks) }
            actions += SheetAction(R.drawable.ic_download, "שמור הכול בטלפון") { downloadAll(tracks) }
        }
        actions += if (Store.isAlbumSaved(list.url)) {
            SheetAction(R.drawable.ic_library_add_check, "הסר מהספרייה") { Store.toggleAlbum(list) }
        } else {
            SheetAction(R.drawable.ic_library_add, "שמור בספרייה") { Store.toggleAlbum(list) }
        }
        if (list.artist.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_person, "לדף של ${list.artist}") { openArtist(ArtistRef(list.artist, list.artistUrl)) }
        }
        actions += shareActions(Format.join(list.title, list.artist), list.shareLink())
        sheet(list.title, list.artist, actions)
    }

    fun linkActions(title: String, link: String) = sheet(title, "", shareActions(title, link))

    fun chooseFolder(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val actions = ArrayList<SheetAction>()
        actions += SheetAction(R.drawable.ic_add, "פלייליסט חדש") {
            askText("פלייליסט חדש", "", "יצירה") { name ->
                val folder = Store.createFolder(name, tracks)
                toast("נוסף ל\"${folder.name}\"")
            }
        }
        for (folder in Store.folders()) {
            actions += SheetAction(R.drawable.ic_queue_music, folder.name) {
                var added = 0
                tracks.forEach { if (Store.addToFolder(folder.id, it)) added++ }
                toast(if (added > 0) "נוסף ל\"${folder.name}\"" else "כבר נמצא בפלייליסט")
            }
        }
        sheet("הוסף לפלייליסט", if (tracks.size == 1) tracks[0].title else "${tracks.size} פריטים", actions)
    }

    fun sheet(title: String, subtitle: String, actions: List<SheetAction>) {
        val dialog = BottomSheetDialog(this)
        val inflater = LayoutInflater.from(dialog.context)
        val column = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, 0, 0, dp(16))
        }
        column.addView(BottomSheetDragHandleView(dialog.context))
        if (title.isNotBlank()) {
            val header = inflater.inflate(R.layout.sheet_header, column, false)
            header.findViewById<TextView>(R.id.sheet_title).text = title
            val sub = header.findViewById<TextView>(R.id.sheet_subtitle)
            sub.text = subtitle
            sub.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
            column.addView(header)
        }
        val tint = ColorStateList.valueOf(color(R.color.q_on_surface_variant))
        for (action in actions) {
            val row = inflater.inflate(R.layout.sheet_row, column, false)
            val icon = row.findViewById<ImageView>(R.id.sheet_icon)
            if (action.icon != 0) {
                icon.setImageResource(action.icon)
                icon.imageTintList = tint
            } else {
                icon.visibility = View.INVISIBLE
            }
            row.findViewById<TextView>(R.id.sheet_text).text = action.text
            row.setOnClickListener {
                dialog.dismiss()
                action.run()
            }
            column.addView(row)
        }
        val scroll = NestedScrollView(dialog.context)
        scroll.addView(column)
        dialog.setContentView(scroll)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    /** A simple list of choices, with a check mark next to the current one. */
    fun choose(title: String, options: List<String>, selected: Int, onChoose: (Int) -> Unit) {
        val actions = options.mapIndexed { i, label ->
            SheetAction(if (i == selected) R.drawable.ic_check_circle_fill else 0, label) { onChoose(i) }
        }
        sheet(title, "", actions)
    }

    fun askText(title: String, initial: String, ok: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            isSingleLine = true
            hint = "שם"
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            withoutWebActions()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(ok) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onOk(value)
            }
            .setNegativeButton("ביטול", null)
            .show()
        input.requestFocus()
    }

    fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("ביטול", null)
            .show()
    }

    fun showText(title: String, text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text.ifBlank { "אין תיאור." })
            .setPositiveButton("סגור", null)
            .show()
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATE_TAB = "tab"
        private const val STATE_PLAYER = "player"
    }
}
