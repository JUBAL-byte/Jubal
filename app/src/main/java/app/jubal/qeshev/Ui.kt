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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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

/** Gradient tiles with a letter. They stand in for cover pictures: squares for albums and songs, circles for artists. */
object Tiles {
    private val PAIRS = arrayOf(
        intArrayOf(0xFF7B5CFF.toInt(), 0xFFFF5CA8.toInt()),
        intArrayOf(0xFF00B4FF.toInt(), 0xFF3D5AFE.toInt()),
        intArrayOf(0xFF00C9A7.toInt(), 0xFF3A86FF.toInt()),
        intArrayOf(0xFFFF8A3D.toInt(), 0xFFFF3D77.toInt()),
        intArrayOf(0xFFB44CFF.toInt(), 0xFF5B3DF5.toInt()),
        intArrayOf(0xFF1FC8A0.toInt(), 0xFF7BCB4A.toInt()),
        intArrayOf(0xFFFFB238.toInt(), 0xFFFF6A3D.toInt()),
        intArrayOf(0xFF3D5AFE.toInt(), 0xFF00C2E0.toInt()),
        intArrayOf(0xFFFF5C8A.toInt(), 0xFFFFA45C.toInt()),
        intArrayOf(0xFF8E5CFF.toInt(), 0xFF00C2FF.toInt()),
    )
    private val BRAND = intArrayOf(0xFF5B3DF5.toInt(), 0xFFE8509E.toInt())

    fun colors(key: String, brand: Boolean = false): IntArray = if (brand) BRAND else PAIRS[abs(key.hashCode() % PAIRS.size)]

    fun letter(key: String): String {
        val c = key.firstOrNull { it.isLetterOrDigit() } ?: return "♪"
        return c.uppercaseChar().toString()
    }

    fun drawable(key: String, round: Boolean, radiusPx: Float, brand: Boolean = false): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors(key, brand))
        if (round) d.shape = GradientDrawable.OVAL else d.cornerRadius = radiusPx
        return d
    }

    fun apply(view: TextView, key: String, round: Boolean = false, brand: Boolean = false, showLetter: Boolean = true, radiusFraction: Float = 0.14f) {
        val context = view.context
        view.text = if (showLetter) letter(key) else ""
        view.setTextColor(context.color(R.color.q_tile_text))
        view.setShadowLayer(8f, 0f, 2f, 0x33000000)
        val bg = drawable(key, round, context.dp(10).toFloat(), brand)
        view.background = bg
        if (!round) {
            view.post {
                bg.cornerRadius = (view.width * radiusFraction).coerceAtLeast(context.dp(6).toFloat())
                view.invalidate()
            }
        }
    }

    fun icon(view: ImageView, icon: Int) {
        if (icon == 0) {
            view.visibility = View.GONE
        } else {
            view.visibility = View.VISIBLE
            view.setImageResource(icon)
            view.imageTintList = ColorStateList.valueOf(view.context.color(R.color.q_tile_text))
        }
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
    val number: Int = 0,
    val onClick: () -> Unit,
    val onMore: (() -> Unit)? = null,
) : Item

class EntityItem(
    val title: String,
    val subtitle: String,
    val tileKey: String,
    val icon: Int = 0,
    val round: Boolean = false,
    val brand: Boolean = false,
    val onClick: () -> Unit,
    val onMore: (() -> Unit)? = null,
) : Item

class MessageItem(val text: String) : Item

class ButtonsItem(val buttons: List<Pair<String, () -> Unit>>) : Item

object LoadingItem : Item

/** A horizontal row of cards, like the shelves on Spotify's home screen. */
class ShelfItem(val key: String, val cards: List<Card>) : Item

/** Half-width shortcut tile (two per row). */
class QuickItem(
    val title: String,
    val tileKey: String,
    val icon: Int = 0,
    val round: Boolean = false,
    val brand: Boolean = false,
    val onClick: () -> Unit,
    val onMore: (() -> Unit)? = null,
) : Item

/** Half-width colored category tile in search. */
class CategoryItem(val title: String, val onClick: () -> Unit) : Item

class ChipsItem(val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : Item

class HeroButton(val label: String, val icon: Int = 0, val style: Int = TONAL, val onClick: () -> Unit) {
    companion object {
        const val FILLED = 0
        const val TONAL = 1
        const val OUTLINED = 2
    }
}

/** The big header of an artist, album, playlist or podcast page. */
class HeroItem(
    val title: String,
    val subtitle: String,
    val tileKey: String,
    val round: Boolean = false,
    val brand: Boolean = false,
    val icon: Int = 0,
    val buttons: List<HeroButton> = emptyList(),
    val onSubtitle: (() -> Unit)? = null,
) : Item

class LogoItem(val version: String) : Item

class Card(
    val title: String,
    val subtitle: String,
    val tileKey: String,
    val round: Boolean = false,
    val icon: Int = 0,
    val brand: Boolean = false,
    val progress: Int = -1,
    val id: String = "",
    val onClick: () -> Unit,
    val onMore: (() -> Unit)? = null,
)

object Rows {
    /** Fills one row view (item_row.xml) for a track. Used by lists and by the queue. */
    fun bindTrack(root: View, item: TrackItem, currentId: String?) {
        val context = root.context
        val t = item.track
        val number = root.findViewById<TextView>(R.id.row_number)
        val tile = root.findViewById<TextView>(R.id.tile)
        val icon = root.findViewById<ImageView>(R.id.tile_icon)
        val title = root.findViewById<TextView>(R.id.row_title)
        val subtitle = root.findViewById<TextView>(R.id.row_subtitle)
        val progress = root.findViewById<LinearProgressIndicator>(R.id.row_progress)
        val more = root.findViewById<ImageButton>(R.id.row_more)

        number.visibility = if (item.number > 0) View.VISIBLE else View.GONE
        number.text = item.number.toString()
        Tiles.icon(icon, 0)
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
        bindMore(root, more, item.onMore)
    }

    fun bindEntity(root: View, item: EntityItem) {
        val context = root.context
        root.findViewById<TextView>(R.id.row_number).visibility = View.GONE
        val tile = root.findViewById<TextView>(R.id.tile)
        val icon = root.findViewById<ImageView>(R.id.tile_icon)
        val title = root.findViewById<TextView>(R.id.row_title)
        val subtitle = root.findViewById<TextView>(R.id.row_subtitle)
        root.findViewById<LinearProgressIndicator>(R.id.row_progress).visibility = View.GONE
        val more = root.findViewById<ImageButton>(R.id.row_more)

        Tiles.apply(tile, item.tileKey, round = item.round, brand = item.brand, showLetter = item.icon == 0)
        Tiles.icon(icon, item.icon)
        title.text = item.title
        title.setTextColor(context.color(R.color.q_on_surface))
        title.paint.isFakeBoldText = false
        subtitle.text = item.subtitle
        subtitle.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
        root.setOnClickListener { item.onClick() }
        bindMore(root, more, item.onMore)
    }

    private fun bindMore(root: View, more: ImageButton, onMore: (() -> Unit)?) {
        if (onMore != null) {
            more.visibility = View.VISIBLE
            more.setOnClickListener { onMore() }
            root.setOnLongClickListener { onMore(); true }
        } else {
            more.visibility = View.GONE
            root.setOnLongClickListener(null)
        }
    }

    fun bindCard(root: View, card: Card, currentId: String?) {
        val context = root.context
        val tile = root.findViewById<TextView>(R.id.card_tile)
        val icon = root.findViewById<ImageView>(R.id.card_icon)
        val title = root.findViewById<TextView>(R.id.card_title)
        val sub = root.findViewById<TextView>(R.id.card_sub)
        val progress = root.findViewById<LinearProgressIndicator>(R.id.card_progress)
        Tiles.apply(tile, card.tileKey, round = card.round, brand = card.brand, showLetter = card.icon == 0, radiusFraction = 0.1f)
        Tiles.icon(icon, card.icon)
        val playing = currentId != null && card.id.isNotEmpty() && card.id == currentId
        title.text = card.title
        title.setTextColor(context.color(if (playing) R.color.q_primary else R.color.q_on_surface))
        title.textAlignment = if (card.round) View.TEXT_ALIGNMENT_CENTER else View.TEXT_ALIGNMENT_VIEW_START
        sub.text = if (playing) "מתנגן עכשיו" else card.subtitle
        sub.textAlignment = title.textAlignment
        sub.visibility = if (sub.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (card.progress in 1..999) {
            progress.visibility = View.VISIBLE
            progress.setProgressCompat(card.progress, false)
        } else {
            progress.visibility = View.GONE
        }
        root.setOnClickListener { card.onClick() }
        val onMore = card.onMore
        if (onMore != null) root.setOnLongClickListener { onMore(); true } else root.setOnLongClickListener(null)
    }
}

class CardsAdapter(private val currentId: () -> String?) : RecyclerView.Adapter<CardsAdapter.Holder>() {
    var cards: List<Card> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = Rows.bindCard(holder.itemView, cards[position], currentId())

    override fun getItemCount(): Int = cards.size
}

class ItemsAdapter(private val currentId: () -> String?) : RecyclerView.Adapter<ItemsAdapter.Holder>() {
    private var ids = LongArray(0)

    var items: List<Item> = emptyList()
        set(value) {
            field = value
            // Stable ids keep keypad focus in place when the list is redrawn.
            val seen = HashMap<String, Int>()
            ids = LongArray(value.size) { i ->
                val base = keyOf(value[i])
                val n = seen.merge(base, 1) { a, b -> a + b } ?: 1
                ("$base#$n").hashCode().toLong()
            }
            notifyDataSetChanged()
        }

    init {
        setHasStableIds(true)
    }

    private fun keyOf(item: Item): String = when (item) {
        is TitleItem -> "title:${item.text}"
        is HeaderItem -> "header:${item.text}"
        is TrackItem -> "track:${item.track.id}"
        is EntityItem -> "entity:${item.title}:${item.subtitle}"
        is MessageItem -> "message:${item.text.take(40)}"
        is ButtonsItem -> "buttons:${item.buttons.joinToString { it.first }}"
        is LoadingItem -> "loading"
        is ShelfItem -> "shelf:${item.key}"
        is QuickItem -> "quick:${item.title}"
        is CategoryItem -> "category:${item.title}"
        is ChipsItem -> "chips:${item.options.joinToString()}"
        is HeroItem -> "hero:${item.title}"
        is LogoItem -> "logo"
    }

    override fun getItemId(position: Int): Long = ids.getOrElse(position) { position.toLong() }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        var shelfKey: String? = null
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is TitleItem -> 0
        is HeaderItem -> 1
        is TrackItem -> 2
        is EntityItem -> 3
        is MessageItem -> 4
        is ButtonsItem -> 5
        is LoadingItem -> 6
        is ShelfItem -> 7
        is QuickItem -> 8
        is CategoryItem -> 9
        is ChipsItem -> 10
        is HeroItem -> 11
        is LogoItem -> 12
    }

    /** Two-column grid: shortcut and category tiles take one column, everything else the full width. */
    fun spanSize(position: Int): Int = when (items.getOrNull(position)) {
        is QuickItem, is CategoryItem -> 1
        else -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.item_title
            1 -> R.layout.item_header
            2, 3 -> R.layout.item_row
            4 -> R.layout.item_message
            5 -> R.layout.item_buttons
            6 -> R.layout.item_loading
            7 -> R.layout.item_shelf
            8 -> R.layout.item_quick
            9 -> R.layout.item_category
            10 -> R.layout.item_chips
            11 -> R.layout.item_hero
            else -> R.layout.item_logo
        }
        val view = inflater.inflate(layout, parent, false)
        if (viewType == 7) {
            val list = view as RecyclerView
            list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = CardsAdapter(currentId)
            list.itemAnimator = null
            list.isNestedScrollingEnabled = false
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val v = holder.itemView
        val context = v.context
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
                val inflater = LayoutInflater.from(context)
                for ((label, action) in item.buttons) {
                    val b = inflater.inflate(R.layout.btn_tonal, box, false) as MaterialButton
                    b.text = label
                    b.setOnClickListener { action() }
                    box.addView(b)
                }
            }
            is LoadingItem -> Unit
            is ShelfItem -> {
                val list = v as RecyclerView
                val adapter = list.adapter as CardsAdapter
                adapter.cards = item.cards
                if (holder.shelfKey != item.key) {
                    holder.shelfKey = item.key
                    list.scrollToPosition(0)
                }
            }
            is QuickItem -> {
                val tile = v.findViewById<TextView>(R.id.quick_tile)
                Tiles.apply(tile, item.tileKey, round = item.round, brand = item.brand, showLetter = item.icon == 0, radiusFraction = 0f)
                Tiles.icon(v.findViewById(R.id.quick_icon), item.icon)
                v.findViewById<TextView>(R.id.quick_title).text = item.title
                val card = v.findViewById<View>(R.id.quick)
                card.setOnClickListener { item.onClick() }
                val onMore = item.onMore
                if (onMore != null) card.setOnLongClickListener { onMore(); true } else card.setOnLongClickListener(null)
            }
            is CategoryItem -> {
                val t = v.findViewById<TextView>(R.id.category)
                t.text = item.title
                t.background = Tiles.drawable(item.title, false, context.dp(12).toFloat())
                t.setOnClickListener { item.onClick() }
            }
            is ChipsItem -> {
                val group = v.findViewById<ChipGroup>(R.id.chips)
                group.setOnCheckedStateChangeListener(null)
                group.removeAllViews()
                val inflater = LayoutInflater.from(context)
                item.options.forEachIndexed { i, label ->
                    val chip = inflater.inflate(R.layout.chip_filter, group, false) as Chip
                    chip.id = View.generateViewId()
                    chip.text = label
                    group.addView(chip)
                    if (i == item.selected) chip.isChecked = true
                    chip.setOnClickListener { if (i != item.selected) item.onSelect(i) }
                }
            }
            is HeroItem -> {
                Tiles.apply(v.findViewById(R.id.hero_tile), item.tileKey, item.round, item.brand, item.icon == 0, 0.08f)
                Tiles.icon(v.findViewById(R.id.hero_icon), item.icon)
                v.findViewById<TextView>(R.id.hero_title).text = item.title
                val sub = v.findViewById<TextView>(R.id.hero_sub)
                sub.text = item.subtitle
                sub.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
                val onSub = item.onSubtitle
                sub.setTextColor(context.color(if (onSub != null) R.color.q_primary else R.color.q_on_surface_variant))
                sub.isClickable = onSub != null
                sub.isFocusable = onSub != null
                if (onSub != null) sub.setOnClickListener { onSub() } else sub.setOnClickListener(null)
                val box = v.findViewById<LinearLayout>(R.id.hero_buttons)
                box.removeAllViews()
                val inflater = LayoutInflater.from(context)
                for (b in item.buttons) {
                    val layout = when (b.style) {
                        HeroButton.FILLED -> R.layout.btn_filled
                        HeroButton.OUTLINED -> R.layout.btn_outlined
                        else -> R.layout.btn_tonal
                    }
                    val button = inflater.inflate(layout, box, false) as MaterialButton
                    button.text = b.label
                    if (b.icon != 0) {
                        button.icon = ContextCompat.getDrawable(context, b.icon)
                        button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    }
                    button.setOnClickListener { b.onClick() }
                    box.addView(button)
                }
                box.visibility = if (item.buttons.isEmpty()) View.GONE else View.VISIBLE
            }
            is LogoItem -> v.findViewById<TextView>(R.id.logo_version).text = item.version
        }
    }

    override fun getItemCount(): Int = items.size
}

fun Context.recycler(adapter: ItemsAdapter): RecyclerView = RecyclerView(this).apply {
    val grid = GridLayoutManager(this@recycler, 2)
    grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int = adapter.spanSize(position)
    }
    layoutManager = grid
    this.adapter = adapter
    clipToPadding = false
    setPadding(0, 0, 0, dp(16))
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

    /** 1234567 → "1.2M". */
    fun big(n: Long): String = when {
        n < 0 -> ""
        n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9).replace(".0B", "B")
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6).replace(".0M", "M")
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3).replace(".0K", "K")
        else -> n.toString()
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
