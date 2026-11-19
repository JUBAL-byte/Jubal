package app.jubal.qeshev

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.color(res: Int): Int = ContextCompat.getColor(this, res)

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Letter tiles: a calm colored square with the first letter. They stand in for cover pictures. */
object Tiles {
    private val LIGHT = intArrayOf(
        0xFFD9E7E3.toInt(), 0xFFE9DDCC.toInt(), 0xFFDCE3F0.toInt(), 0xFFEADBE2.toInt(),
        0xFFE3E6D3.toInt(), 0xFFD8E6EE.toInt(), 0xFFEDE0D4.toInt(), 0xFFDDD9EE.toInt(),
    )
    private val DARK = intArrayOf(
        0xFF2C3E3A.toInt(), 0xFF3D3428.toInt(), 0xFF2D3446.toInt(), 0xFF3C2E35.toInt(),
        0xFF353A2A.toInt(), 0xFF293A42.toInt(), 0xFF3E3228.toInt(), 0xFF312E44.toInt(),
    )

    fun colorFor(context: Context, key: String): Int {
        val palette = if (context.isNight()) DARK else LIGHT
        return palette[abs(key.hashCode() % palette.size)]
    }

    fun letter(key: String): String {
        val c = key.firstOrNull { it.isLetterOrDigit() } ?: return "♪"
        return c.uppercaseChar().toString()
    }

    fun apply(view: TextView, key: String, radiusFraction: Float = 0.28f) {
        val context = view.context
        view.text = letter(key)
        view.setTextColor(context.color(R.color.q_tile_text))
        val bg = GradientDrawable()
        bg.setColor(colorFor(context, key))
        view.post {
            bg.cornerRadius = view.width * radiusFraction
            view.invalidate()
        }
        bg.cornerRadius = context.dp(12).toFloat()
        view.background = bg
    }
}

/**
 * Removes "web search", "translate" and similar entries from the text-selection
 * menu, so nothing in the app can open a browser.
 */
fun EditText.withoutWebActions() {
    val allowed = setOf(
        android.R.id.cut, android.R.id.copy, android.R.id.paste,
        android.R.id.selectAll, android.R.id.pasteAsPlainText,
    )
    val callback = object : ActionMode.Callback {
        private fun strip(menu: Menu) {
            var guard = 0
            while (guard++ < 60) {
                val extra = (0 until menu.size()).map { menu.getItem(it) }.firstOrNull { it.itemId !in allowed } ?: break
                menu.removeItem(extra.itemId)
            }
        }
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { strip(menu); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { strip(menu); return true }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode) {}
    }
    customSelectionActionModeCallback = callback
    customInsertionActionModeCallback = callback
}

// ---------------------------------------------------------------------- list items

sealed interface Item

class TitleItem(val text: String, val subtitle: String = "") : Item

class HeaderItem(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Item

class TrackItem(
    val track: Track,
    val subtitle: String,
    val progress: Int = -1,
    val onClick: () -> Unit,
    val onMore: (() -> Unit)? = null,
) : Item

class EntityItem(
    val title: String,
    val subtitle: String,
    val tileKey: String,
    val icon: Int = 0,
    val onClick: () -> Unit,
    val onMore: (() -> Unit)? = null,
) : Item

class MessageItem(val text: String) : Item

class ButtonsItem(val buttons: List<Pair<String, () -> Unit>>) : Item

object LoadingItem : Item

object Rows {
    /** Fills one row view (item_row.xml) for a track. Used by lists and by the queue. */
    fun bindTrack(root: View, item: TrackItem, currentId: String?) {
        val context = root.context
        val t = item.track
        val tile = root.findViewById<TextView>(R.id.tile)
        val icon = root.findViewById<ImageView>(R.id.tile_icon)
        val title = root.findViewById<TextView>(R.id.row_title)
        val subtitle = root.findViewById<TextView>(R.id.row_subtitle)
        val progress = root.findViewById<LinearProgressIndicator>(R.id.row_progress)
        val more = root.findViewById<ImageButton>(R.id.row_more)

        icon.visibility = View.GONE
        Tiles.apply(tile, t.artist.ifBlank { t.title })
        val playing = currentId != null && currentId == t.id
        title.text = t.title
        title.setTextColor(context.color(if (playing) R.color.q_primary else R.color.q_on_surface))
        title.paint.isFakeBoldText = playing
        subtitle.text = if (playing) "מתנגן עכשיו" else item.subtitle
        subtitle.visibility = if (subtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (item.progress in 0..1000 && t.kind != Kind.MUSIC) {
            progress.visibility = View.VISIBLE
            progress.setProgressCompat(item.progress, false)
        } else {
            progress.visibility = View.GONE
        }
        root.setOnClickListener { item.onClick() }
        val onMore = item.onMore
        if (onMore != null) {
            more.visibility = View.VISIBLE
            more.setOnClickListener { onMore() }
            root.setOnLongClickListener { onMore(); true }
        } else {
            more.visibility = View.GONE
            root.setOnLongClickListener(null)
        }
    }

    fun bindEntity(root: View, item: EntityItem) {
        val context = root.context
        val tile = root.findViewById<TextView>(R.id.tile)
        val icon = root.findViewById<ImageView>(R.id.tile_icon)
        val title = root.findViewById<TextView>(R.id.row_title)
        val subtitle = root.findViewById<TextView>(R.id.row_subtitle)
        val progress = root.findViewById<LinearProgressIndicator>(R.id.row_progress)
        val more = root.findViewById<ImageButton>(R.id.row_more)

        Tiles.apply(tile, item.tileKey)
        if (item.icon != 0) {
            tile.text = ""
            icon.visibility = View.VISIBLE
            icon.setImageResource(item.icon)
            icon.imageTintList = ColorStateList.valueOf(context.color(R.color.q_tile_text))
        } else {
            icon.visibility = View.GONE
        }
        title.text = item.title
        title.setTextColor(context.color(R.color.q_on_surface))
        title.paint.isFakeBoldText = false
        subtitle.text = item.subtitle
        subtitle.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
        progress.visibility = View.GONE
        root.setOnClickListener { item.onClick() }
        val onMore = item.onMore
        if (onMore != null) {
            more.visibility = View.VISIBLE
            more.setOnClickListener { onMore() }
            root.setOnLongClickListener { onMore(); true }
        } else {
            more.visibility = View.GONE
            root.setOnLongClickListener(null)
        }
    }
}

class ItemsAdapter(private val currentId: () -> String?) : RecyclerView.Adapter<ItemsAdapter.Holder>() {
    var items: List<Item> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is TitleItem -> 0
        is HeaderItem -> 1
        is TrackItem -> 2
        is EntityItem -> 3
        is MessageItem -> 4
        is ButtonsItem -> 5
        is LoadingItem -> 6
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.item_title
            1 -> R.layout.item_header
            2, 3 -> R.layout.item_row
            4 -> R.layout.item_message
            5 -> R.layout.item_buttons
            else -> R.layout.item_loading
        }
        return Holder(inflater.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val v = holder.itemView
        when (val item = items[position]) {
            is TitleItem -> {
                v.findViewById<TextView>(R.id.title_text).text = item.text
                val sub = v.findViewById<TextView>(R.id.title_sub)
                sub.text = item.subtitle
                sub.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
            }
            is HeaderItem -> {
                v.findViewById<TextView>(R.id.header_text).text = item.text
                val action = v.findViewById<TextView>(R.id.header_action)
                val onAction = item.onAction
                if (item.action != null && onAction != null) {
                    action.visibility = View.VISIBLE
                    action.text = item.action
                    action.setOnClickListener { onAction() }
                } else {
                    action.visibility = View.GONE
                }
            }
            is TrackItem -> Rows.bindTrack(v, item, currentId())
            is EntityItem -> Rows.bindEntity(v, item)
            is MessageItem -> v.findViewById<TextView>(R.id.message).text = item.text
            is ButtonsItem -> {
                val box = v.findViewById<LinearLayout>(R.id.buttons_box)
                box.removeAllViews()
                val inflater = LayoutInflater.from(v.context)
                for ((label, action) in item.buttons) {
                    val b = inflater.inflate(R.layout.btn_tonal, box, false) as MaterialButton
                    b.text = label
                    b.setOnClickListener { action() }
                    box.addView(b)
                }
            }
            is LoadingItem -> Unit
        }
    }

    override fun getItemCount(): Int = items.size
}

fun Context.recycler(adapter: ItemsAdapter): RecyclerView = RecyclerView(this).apply {
    layoutManager = LinearLayoutManager(this@recycler)
    this.adapter = adapter
    clipToPadding = false
    setPadding(0, 0, 0, dp(12))
    itemAnimator = null
}

object Format {
    fun time(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun duration(seconds: Long): String = if (seconds <= 0) "" else time(seconds * 1000)

    fun date(ms: Long): String {
        if (ms <= 0) return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val days = ((now.timeInMillis - ms) / 86_400_000L)
        return when {
            days < 1 && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "היום"
            days < 2 -> "אתמול"
            days < 7 -> "לפני $days ימים"
            else -> SimpleDateFormat("d.M.yyyy", Locale.US).format(Date(ms))
        }
    }

    fun remaining(ms: Long): String {
        if (ms <= 0) return ""
        val minutes = (ms / 60_000).coerceAtLeast(1)
        return if (minutes >= 60) "נשארו ${minutes / 60} שע׳ ${minutes % 60} דק׳" else "נשארו $minutes דק׳"
    }

    fun count(n: Int, one: String, many: String): String = if (n == 1) "$one אחד" else "$n $many"

    fun join(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(" · ")

    fun speed(value: Float): String {
        val text = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        return "$text×"
    }

    fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "בוקר טוב"
            in 12..16 -> "צהריים טובים"
            in 17..20 -> "ערב טוב"
            else -> "לילה טוב"
        }
    }
}

