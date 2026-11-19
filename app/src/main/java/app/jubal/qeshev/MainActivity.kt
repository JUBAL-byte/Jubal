package app.jubal.qeshev

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.io.File

class SheetAction(val icon: Int, val text: String, val run: () -> Unit)

class MainActivity : AppCompatActivity() {
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var backButton: ImageButton
    private lateinit var topAction: ImageButton
    private lateinit var nav: BottomNavigationView
    private lateinit var mini: View
    private lateinit var miniTile: TextView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniProgress: LinearProgressIndicator
    private lateinit var playerPanel: PlayerPanel

    private val tabIds = intArrayOf(R.id.nav_home, R.id.nav_podcasts, R.id.nav_videos, R.id.nav_music, R.id.nav_library)
    private val stacks = List(5) { ArrayList<Screen>() }
    private var tab = 0

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set
    private val pendingActions = ArrayList<(MediaController) -> Unit>()
    private var lastItemId: String? = null

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
        miniTile = findViewById(R.id.mini_tile)
        miniTitle = findViewById(R.id.mini_title)
        miniArtist = findViewById(R.id.mini_artist)
        miniPlay = findViewById(R.id.mini_play)
        miniProgress = findViewById(R.id.mini_progress)
        playerPanel = PlayerPanel(this, findViewById(R.id.player_overlay))

        backButton.setOnClickListener { goBack() }
        topAction.setOnClickListener { stacks[tab].lastOrNull()?.onTopAction() }
        mini.setOnClickListener { playerPanel.show() }
        miniPlay.setOnClickListener { togglePlay() }

        val startTab = (savedInstanceState?.getInt(STATE_TAB) ?: 0).coerceIn(0, 4)
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

        if (savedInstanceState?.getBoolean(STATE_SETTINGS) == true) push(SettingsScreen(this))
        if (savedInstanceState?.getBoolean(STATE_PLAYER) == true) playerPanel.show(animate = false)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null } ?: return@addListener
            controller = c
            c.addListener(playerListener)
            val actions = pendingActions.toList()
            pendingActions.clear()
            actions.forEach { it(c) }
            onPlayerChanged()
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
        outState.putBoolean(STATE_SETTINGS, stacks[tab].lastOrNull() is SettingsScreen)
        outState.putBoolean(STATE_PLAYER, playerPanel.isShown)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A hand-picked display size is applied when the screen is built, so rebuild it after a rotation.
        if (Settings.isCustomScale(this)) recreate()
    }

    // ------------------------------------------------------------------ navigation

    private fun rootFor(index: Int): Screen = when (index) {
        0 -> HomeScreen(this)
        1 -> PodcastsScreen(this)
        2 -> VideosScreen(this)
        3 -> MusicScreen(this)
        else -> LibraryScreen(this)
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
            if (stack.isEmpty()) showTab(index)
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
            Tiles.apply(miniTile, track.artist.ifBlank { track.title })
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

    /** Plays a list, starting from one item. Podcasts open the full player, music stays in the list. */
    fun playTracks(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        val i = index.coerceIn(0, tracks.size - 1)
        val first = tracks[i]
        val start = if (first.kind == Kind.MUSIC) 0L else Store.position(first.id)
        val items = tracks.map { it.toMediaItem() }
        withController { c ->
            c.setMediaItems(items, i, start)
            c.prepare()
            c.play()
        }
        if (first.kind == Kind.PODCAST) playerPanel.show()
    }

    fun playOne(track: Track) = playTracks(listOf(track), 0)

    fun shuffle(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        playTracks(tracks.shuffled(), 0)
    }

    fun playNext(track: Track) {
        withController { c ->
            if (c.mediaItemCount == 0) {
                playOne(track)
            } else {
                c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
                toast("יתנגן אחרי הנוכחי")
            }
        }
    }

    fun addToQueue(track: Track) {
        withController { c ->
            if (c.mediaItemCount == 0) {
                playOne(track)
            } else {
                c.addMediaItem(track.toMediaItem())
                toast("נוסף לתור")
            }
        }
    }

    fun addAllToQueue(tracks: List<Track>) {
        withController { c ->
            if (c.mediaItemCount == 0) {
                playTracks(tracks, 0)
            } else {
                c.addMediaItems(tracks.map { it.toMediaItem() })
                toast("נוסף לתור")
            }
        }
    }

    fun toggleFavorite(track: Track) {
        val now = Store.toggleFavorite(track)
        toast(if (now) "נוסף לאהובים" else "הוסר מהאהובים")
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

    fun openPodcast(feedUrl: String, name: String) {
        push(PodcastScreen(this, feedUrl, name))
    }

    // ------------------------------------------------------------------ sheets and dialogs

    fun trackActions(track: Track, extra: List<SheetAction> = emptyList(), description: String? = null) {
        val actions = ArrayList<SheetAction>()
        actions += SheetAction(R.drawable.ic_play_arrow, "נגן עכשיו") { playOne(track) }
        actions += SheetAction(R.drawable.ic_playlist_play, "נגן אחרי הנוכחי") { playNext(track) }
        actions += SheetAction(R.drawable.ic_queue_music, "הוסף לתור") { addToQueue(track) }
        actions += if (Store.isFavorite(track.id)) {
            SheetAction(R.drawable.ic_favorite_fill, "הסר מהאהובים") { toggleFavorite(track) }
        } else {
            SheetAction(R.drawable.ic_favorite, "הוסף לאהובים") { toggleFavorite(track) }
        }
        actions += SheetAction(R.drawable.ic_create_new_folder, "הוסף לתיקייה") { chooseFolder(listOf(track)) }
        actions += if (Store.downloadFor(track.id) != null) {
            SheetAction(R.drawable.ic_delete, "מחק מהטלפון") { deleteDownload(track) }
        } else {
            SheetAction(R.drawable.ic_download, "שמור בטלפון") { download(track) }
        }
        if (track.kind == Kind.PODCAST && track.feedUrl.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_podcasts, "לעמוד הפודקאסט") { openPodcast(track.feedUrl, track.artist) }
        }
        if (track.kind == Kind.PODCAST && Store.position(track.id) > 0) {
            actions += SheetAction(R.drawable.ic_replay_10, "התחל מההתחלה") {
                Store.clearPosition(track.id)
                playOne(track)
            }
        }
        if (description != null) {
            actions += SheetAction(R.drawable.ic_info, "תיאור") { showText(track.title, description) }
        }
        actions += extra
        sheet(track.title, Format.join(track.artist, track.kind.label), actions)
    }

    fun chooseFolder(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val actions = ArrayList<SheetAction>()
        actions += SheetAction(R.drawable.ic_add, "תיקייה חדשה") {
            askText("תיקייה חדשה", "", "יצירה") { name ->
                val folder = Store.createFolder(name, tracks)
                toast("נוסף לתיקייה \"${folder.name}\"")
            }
        }
        for (folder in Store.folders()) {
            actions += SheetAction(R.drawable.ic_folder, folder.name) {
                var added = 0
                tracks.forEach { if (Store.addToFolder(folder.id, it)) added++ }
                toast(if (added > 0) "נוסף לתיקייה \"${folder.name}\"" else "כבר נמצא בתיקייה")
            }
        }
        sheet("הוסף לתיקייה", if (tracks.size == 1) tracks[0].title else "${tracks.size} פריטים", actions)
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
        val header = inflater.inflate(R.layout.sheet_header, column, false)
        header.findViewById<TextView>(R.id.sheet_title).text = title
        val sub = header.findViewById<TextView>(R.id.sheet_subtitle)
        sub.text = subtitle
        sub.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        column.addView(header)
        val tint = ColorStateList.valueOf(color(R.color.q_on_surface_variant))
        for (action in actions) {
            val row = inflater.inflate(R.layout.sheet_row, column, false)
            val icon = row.findViewById<ImageView>(R.id.sheet_icon)
            if (action.icon != 0) {
                icon.setImageResource(action.icon)
                icon.imageTintList = tint
            } else {
                icon.visibility = View.GONE
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
        private const val STATE_SETTINGS = "settings"
        private const val STATE_PLAYER = "player"
    }
}
