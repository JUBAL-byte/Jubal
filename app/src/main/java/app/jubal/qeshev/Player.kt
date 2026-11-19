package app.jubal.qeshev

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** The full-screen player that slides up over the app. Text only: a letter tile stands in for a cover. */
class PlayerPanel(private val act: MainActivity, private val overlay: FrameLayout) {
    private val root: View = LayoutInflater.from(act).inflate(R.layout.view_player, overlay, false)
    private val label: TextView
    private val tile: TextView
    private val title: TextView
    private val artist: TextView
    private val seek: Slider
    private val pos: TextView
    private val dur: TextView
    private val play: FloatingActionButton
    private val prev: ImageButton
    private val next: ImageButton
    private val fav: ImageButton
    private val download: ImageButton
    private val speed: MaterialButton
    private val autoplay: MaterialSwitch
    private val queueHeader: TextView
    private val queueList: LinearLayout

    var isShown = false
        private set
    private var touching = false
    private var queueSignature = ""
    private var binding = false

    init {
        overlay.addView(root)
        label = root.findViewById(R.id.player_label)
        tile = root.findViewById(R.id.player_tile)
        title = root.findViewById(R.id.player_title)
        artist = root.findViewById(R.id.player_artist)
        seek = root.findViewById(R.id.player_seek)
        pos = root.findViewById(R.id.player_pos)
        dur = root.findViewById(R.id.player_dur)
        play = root.findViewById(R.id.player_play)
        prev = root.findViewById(R.id.player_prev)
        next = root.findViewById(R.id.player_next)
        fav = root.findViewById(R.id.player_fav)
        download = root.findViewById(R.id.player_download)
        speed = root.findViewById(R.id.player_speed)
        autoplay = root.findViewById(R.id.player_autoplay)
        queueHeader = root.findViewById(R.id.queue_header)
        queueList = root.findViewById(R.id.queue_list)

        root.findViewById<View>(R.id.player_close).setOnClickListener { hide() }
        root.findViewById<View>(R.id.player_more).setOnClickListener { act.currentTrack()?.let { act.trackActions(it) } }
        play.setOnClickListener { act.togglePlay() }
        prev.setOnClickListener { act.controller?.seekToPrevious() }
        next.setOnClickListener { act.controller?.seekToNext() }
        root.findViewById<View>(R.id.player_back).setOnClickListener { act.controller?.seekBack() }
        root.findViewById<View>(R.id.player_fwd).setOnClickListener { act.controller?.seekForward() }
        fav.setOnClickListener { act.currentTrack()?.let { act.toggleFavorite(it) } }
        root.findViewById<View>(R.id.player_folder).setOnClickListener {
            act.currentTrack()?.let { act.chooseFolder(listOf(it)) }
        }
        download.setOnClickListener {
            val t = act.currentTrack() ?: return@setOnClickListener
            if (Store.downloadFor(t.id) != null) act.toast("כבר שמור בטלפון") else act.download(t)
        }
        artist.setOnClickListener {
            val t = act.currentTrack() ?: return@setOnClickListener
            if (t.kind == Kind.PODCAST && t.feedUrl.isNotBlank()) {
                hide()
                act.openPodcast(t.feedUrl, t.artist)
            }
        }
        speed.setOnClickListener { nextSpeed() }
        autoplay.setOnCheckedChangeListener { _, checked ->
            if (!binding) Store.setAutoplay(checked)
        }

        seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                touching = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                touching = false
                seekTo(slider.value)
            }
        })
        seek.addOnChangeListener { _, value, fromUser ->
            // Keypad and accessibility changes arrive without a touch
            if (fromUser && !touching) seekTo(value)
            if (fromUser) {
                val c = act.controller
                if (c != null && c.duration > 0) pos.text = Format.time((c.duration * value / 1000f).toLong())
            }
        }
    }

    fun show(animate: Boolean = true) {
        if (isShown) return
        isShown = true
        overlay.visibility = View.VISIBLE
        if (animate) {
            val h = act.window.decorView.height.toFloat()
            overlay.translationY = if (h > 0) h else 2000f
            overlay.animate().translationY(0f).setDuration(220).start()
        } else {
            overlay.translationY = 0f
        }
        queueSignature = ""
        update()
        play.requestFocus()
    }

    fun hide() {
        if (!isShown) return
        isShown = false
        val h = act.window.decorView.height.toFloat()
        overlay.animate().translationY(if (h > 0) h else 2000f).setDuration(180).withEndAction {
            if (!isShown) overlay.visibility = View.GONE
        }.start()
    }

    private fun seekTo(value: Float) {
        val c = act.controller ?: return
        val duration = c.duration
        if (duration > 0) c.seekTo((duration * value / 1000f).toLong())
    }

    private fun nextSpeed() {
        val c = act.controller ?: return
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
        val current = c.playbackParameters.speed
        val index = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        val value = speeds[(index + 1) % speeds.size]
        c.setPlaybackSpeed(value)
        Store.setSpeed(value)
        speed.text = Format.speed(value)
    }

    fun update() {
        if (!isShown) return
        val c = act.controller
        val item = c?.currentMediaItem
        val t = Track.of(item)
        if (c == null || t == null) {
            label.text = ""
            title.text = "לא מתנגן כלום כרגע"
            artist.text = "בחר משהו לשמוע מהלשוניות למטה"
            tile.text = ""
            tile.background = null
            pos.text = ""
            dur.text = ""
            seek.value = 0f
            play.setImageResource(R.drawable.ic_play_arrow_fill)
            queueHeader.visibility = View.GONE
            queueList.removeAllViews()
            queueSignature = ""
            autoplay.visibility = View.GONE
            return
        }

        label.text = "מתנגן · ${t.kind.label}"
        if (title.text.toString() != t.title) {
            Tiles.apply(tile, t.artist.ifBlank { t.title }, 0.22f)
            title.text = t.title
            artist.text = t.artist
        }

        val position = c.currentPosition
        val duration = c.duration
        val buffering = c.playbackState == Player.STATE_BUFFERING
        pos.text = Format.time(position)
        dur.text = when {
            duration > 0 -> Format.time(duration)
            buffering -> "טוען…"
            else -> ""
        }
        if (!touching && duration > 0) {
            seek.value = ((position * 1000f) / duration).coerceIn(0f, 1000f)
        }
        play.setImageResource(if (c.playWhenReady) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill)
        play.contentDescription = if (c.playWhenReady) "השהה" else "נגן"
        prev.alpha = if (c.hasPreviousMediaItem() || position > 3000) 1f else 0.4f
        next.alpha = if (c.hasNextMediaItem()) 1f else 0.4f

        val favorite = Store.isFavorite(t.id)
        fav.setImageResource(if (favorite) R.drawable.ic_favorite_fill else R.drawable.ic_favorite)
        fav.imageTintList = ColorStateList.valueOf(act.color(if (favorite) R.color.q_primary else R.color.q_on_surface_variant))
        val saved = Store.downloadFor(t.id) != null
        download.setImageResource(if (saved) R.drawable.ic_download_done else R.drawable.ic_download)
        speed.text = Format.speed(c.playbackParameters.speed)

        binding = true
        autoplay.visibility = if (t.kind == Kind.PODCAST) View.GONE else View.VISIBLE
        autoplay.isChecked = Store.autoplay()
        binding = false

        updateQueue(t)
    }

    private fun updateQueue(current: Track) {
        val c = act.controller ?: return
        val index = c.currentMediaItemIndex
        val count = c.mediaItemCount
        val ids = StringBuilder().append(index).append('/').append(count)
        val end = minOf(count, index + 31)
        for (i in index + 1 until end) ids.append(',').append(c.getMediaItemAt(i).mediaId)
        val signature = ids.toString()
        if (signature == queueSignature) return
        queueSignature = signature

        queueList.removeAllViews()
        queueHeader.visibility = View.VISIBLE
        if (index + 1 >= count) {
            queueHeader.text = "הבא בתור"
            val empty = TextView(act).apply {
                text = if (current.kind != Kind.PODCAST && Store.autoplay()) {
                    "התור ריק. כשהשמעה תסתיים יתווסף משהו דומה."
                } else {
                    "התור ריק. מכל פריט אפשר לבחור \"הוסף לתור\"."
                }
                setTextColor(act.color(R.color.q_on_surface_variant))
                textSize = 14f
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(8))
            }
            queueList.addView(empty)
            return
        }
        queueHeader.text = "הבא בתור · ${count - index - 1}"
        val inflater = LayoutInflater.from(act)
        for (i in index + 1 until end) {
            val t = Track.of(c.getMediaItemAt(i)) ?: continue
            val row = inflater.inflate(R.layout.item_row, queueList, false)
            Rows.bindTrack(
                row,
                TrackItem(
                    track = t,
                    subtitle = Format.join(t.artist, Format.duration(t.durationSec)),
                    onClick = { act.controller?.seekToDefaultPosition(i) },
                    onMore = {
                        act.sheet(
                            t.title, t.artist,
                            listOf(
                                SheetAction(R.drawable.ic_play_arrow, "נגן עכשיו") { act.controller?.seekToDefaultPosition(i) },
                                SheetAction(R.drawable.ic_close, "הסר מהתור") { act.controller?.removeMediaItem(i) },
                                SheetAction(R.drawable.ic_favorite, if (Store.isFavorite(t.id)) "הסר מהאהובים" else "הוסף לאהובים") {
                                    act.toggleFavorite(t)
                                },
                                SheetAction(R.drawable.ic_create_new_folder, "הוסף לתיקייה") { act.chooseFolder(listOf(t)) },
                            ),
                        )
                    },
                ),
                null,
            )
            queueList.addView(row)
        }
    }
}
