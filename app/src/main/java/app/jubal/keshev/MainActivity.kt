package app.jubal.keshev

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var backButton: Button
    private lateinit var mini: View
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: Button
    private lateinit var tabButtons: List<Button>

    private val stack = ArrayList<Screen>()
    private lateinit var tabRoots: List<Screen>
    private var currentTab = 0

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set
    private val pendingActions = ArrayList<(MediaController) -> Unit>()
    private val playerWatchers = LinkedHashSet<() -> Unit>()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = onPlayerChanged()
        override fun onPlayerError(error: PlaybackException) {
            toast("ההשמעה נכשלה. נסה שוב, או בדוק את החיבור לאינטרנט.")
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        titleView = findViewById(R.id.title)
        backButton = findViewById(R.id.back)
        mini = findViewById(R.id.mini)
        miniTitle = findViewById(R.id.mini_title)
        miniPlay = findViewById(R.id.mini_play)
        tabButtons = listOf(
            findViewById<Button>(R.id.tab_search),
            findViewById<Button>(R.id.tab_library),
            findViewById<Button>(R.id.tab_downloads),
        )

        tabRoots = listOf(SearchScreen(this), LibraryScreen(this), DownloadsScreen(this))
        tabButtons.forEachIndexed { index, b -> b.setOnClickListener { showTab(index) } }
        backButton.setOnClickListener { goBack() }
        mini.setOnClickListener { openPlayer() }
        miniPlay.setOnClickListener { togglePlay() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        showTab(0)

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
        stack.lastOrNull()?.onShown()
    }

    override fun onStop() {
        stack.lastOrNull()?.onHidden()
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    // Navigation

    fun push(screen: Screen) {
        stack.lastOrNull()?.onHidden()
        stack.add(screen)
        display(screen)
    }

    private fun goBack(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1).onHidden()
            display(stack.last())
            return true
        }
        if (currentTab != 0) {
            showTab(0)
            return true
        }
        return false
    }

    private fun showTab(index: Int) {
        stack.lastOrNull()?.onHidden()
        stack.clear()
        currentTab = index
        tabButtons.forEachIndexed { i, b ->
            b.setTypeface(null, if (i == index) Typeface.BOLD else Typeface.NORMAL)
            b.alpha = if (i == index) 1f else 0.75f
        }
        stack.add(tabRoots[index])
        display(tabRoots[index])
    }

    private fun display(screen: Screen) {
        val v = screen.view()
        (v.parent as? ViewGroup)?.removeView(v)
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        titleView.text = screen.title
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        screen.onShown()
    }

    fun refreshTitle(screen: Screen) {
        if (stack.lastOrNull() === screen) titleView.text = screen.title
    }

    fun openPlayer() {
        if (stack.lastOrNull() is PlayerScreen) return
        push(PlayerScreen(this))
    }

    // Playback

    fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pendingActions += action
    }

    fun watchPlayer(watcher: () -> Unit) { playerWatchers += watcher }
    fun unwatchPlayer(watcher: () -> Unit) { playerWatchers -= watcher }

    private fun onPlayerChanged() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null || c.playbackState == Player.STATE_IDLE && !c.playWhenReady) {
            mini.visibility = View.GONE
        } else {
            mini.visibility = View.VISIBLE
            miniTitle.text = item.mediaMetadata.title ?: ""
            miniPlay.text = if (c.playWhenReady) "השהה" else "נגן"
        }
        playerWatchers.toList().forEach { it() }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.playWhenReady) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /** Plays an episode. Saved files are used first; YouTube links are turned into an audio stream. */
    fun play(ref: PlayRef) {
        Store.setLast(ref)
        val saved = Store.savedFileFor(ref.mediaId)
        if (saved != null) {
            start(ref, saved.uri)
            return
        }
        if (ref.kind == "yt") {
            toast("טוען שמע…")
            lifecycleScope.launch {
                val audio = try {
                    withContext(Dispatchers.IO) { YouTube.resolveAudio(ref.ref) }
                } catch (e: Exception) {
                    toast("לא הצלחתי לטעון את השמע מיוטיוב. נסה שוב בעוד רגע.")
                    return@launch
                }
                start(ref, audio.url)
            }
        } else {
            start(ref, ref.ref)
        }
    }

    private fun start(ref: PlayRef, uri: String) {
        val item = MediaItem.Builder()
            .setMediaId(ref.mediaId)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ref.title).setArtist(ref.artist).build())
            .build()
        val startAt = Store.position(ref.mediaId)
        withController { c ->
            c.setMediaItem(item, startAt)
            c.prepare()
            c.play()
        }
        openPlayer()
    }

    fun download(ref: PlayRef) {
        if (Store.savedFileFor(ref.mediaId) != null) {
            toast("הפרק כבר שמור בטלפון")
            return
        }
        DownloadService.start(this, ref)
        toast("ההורדה התחילה. אפשר לראות אותה בהתראות.")
    }

    // Small helpers

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    fun choose(title: String, options: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("ביטול", null)
            .show()
    }

    fun showText(title: String, text: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text.ifBlank { "אין תיאור." })
            .setPositiveButton("סגור", null)
            .show()
    }
}
