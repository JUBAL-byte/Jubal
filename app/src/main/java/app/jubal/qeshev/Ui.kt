package app.jubal.qeshev

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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

/** Accent colors for search categories. Nothing in the app shows pictures or letter tiles. */
object Accents {
    // Quiet, muted tones that sit well on light and dark backgrounds
    private val COLORS = intArrayOf(
        0xFF8C84B8.toInt(), 0xFF7D9CB5.toInt(), 0xFF7FA796.toInt(), 0xFFB39A80.toInt(), 0xFFA58AA3.toInt(),
        0xFF8E9AB0.toInt(), 0xFFA4A07F.toInt(), 0xFFB08A8A.toInt(), 0xFF86A3A8.toInt(), 0xFF9C8FB3.toInt(),
    )

    fun of(key: String): Int = COLORS[abs(key.hashCode() % COLORS.size)]

    /** A rounded surface with a thin colored bar on the start side. */
    fun category(context: Context, key: String): LayerDrawable {
        val radius = context.dp(12).toFloat()
        val bar = GradientDrawable().apply {
            cornerRadius = radius
            setColor(of(key))
        }
        val surface = GradientDrawable().apply {
            cornerRadius = radius
            setColor(context.color(R.color.q_surface_container_high))
        }
        return LayerDrawable(arrayOf(bar, surface)).apply {
            setLayerInsetRelative(1, context.dp(4), 0, 0, 0)
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

/** A heading. [sub] marks a smaller one inside a [SectionItem]. */
class HeaderItem(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val sub: Boolean = false) : Item

/** A main part of a page (on home: continue, music, podcasts, recommendations), set off by a thin line. */
class SectionItem(val text: String, val first: Boolean = false) : Item

class TrackItem(
    val track: Track,
    val subtitle: String,
    val progress: Int = -1,
    val number: Int = 0,
    val onClick: () -> Unit,
    val onMore: (() -> Unit)? = null,
) : Item

/** An artist, album, podcast, playlist or setting. [icon] marks what it is; [tileKey] only keeps rows apart. */
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

/**
 * A group of cards under a header. Shown as a short list with "show more",
 * or as name pills when every card is an artist.
 */
class ShelfItem(val key: String, val cards: List<Card>) : Item

/** Search category with a colored accent. */
class CategoryItem(val title: String, val onClick: () -> Unit) : Item

class ChipsItem(val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : Item

class HeroButton(val label: String, val icon: Int = 0, val style: Int = TONAL, val onClick: () -> Unit) {
    companion object {
        const val FILLED = 0
        const val TONAL = 1
        const val OUTLINED = 2
    }
}

/** The header of an artist, album, playlist or podcast page: what it is, a big title and actions. */
class HeroItem(
    val title: String,
    val subtitle: String,
    val tileKey: String,
    val round: Boolean = false,
    val brand: Boolean = false,
    val icon: Int = 0,
    val buttons: List<HeroButton> = emptyList(),
    val onSubtitle: (() -> Unit)? = null,
    val kind: String = "",
) : Item

class LogoItem(val version: String) : Item

/** One entry of a [ShelfItem]. [round] marks an artist; [icon] marks albums, podcasts and playlists. */
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

// Display-only items the adapter makes out of shelves
private class CardRowItem(val shelf: String, val card: Card) : Item
private class PillsItem(val key: String, val cards: List<Card>) : Item
private class MoreItem(val key: String, val hidden: Int) : Item

object Rows {
    private fun icon(view: ImageView, res: Int, highlight: Boolean) {
        if (res == 0) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.setImageResource(res)
        view.imageTintList = ColorStateList.valueOf(
            view.context.color(if (highlight) R.color.q_primary else R.color.q_on_surface_variant)
        )
    }

    private fun fill(
        root: View,
        title: String,
        subtitle: String,
        iconRes: Int,
        highlight: Boolean,
        playing: Boolean,
        number: Int,
        progress: Int,
        onClick: () -> Unit,
        onMore: (() -> Unit)?,
    ) {
        val context = root.context
        val numberView = root.findViewById<TextView>(R.id.row_number)
        val titleView = root.findViewById<TextView>(R.id.row_title)
        val subView = root.findViewById<TextView>(R.id.row_subtitle)
        val progressView = root.findViewById<LinearProgressIndicator>(R.id.row_progress)

        numberView.visibility = if (number > 0 && !playing) View.VISIBLE else View.GONE
        numberView.text = number.toString()
        icon(root.findViewById(R.id.row_icon), if (playing) R.drawable.ic_graphic_eq else if (number > 0) 0 else iconRes, playing || highlight)
        titleView.text = title
        titleView.setTextColor(context.color(if (playing) R.color.q_primary else R.color.q_on_surface))
        subView.text = if (playing) Format.join("מתנגן עכשיו", subtitle) else subtitle
        subView.visibility = if (subView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (progress in 1..999) {
            progressView.visibility = View.VISIBLE
            progressView.setProgressCompat(progress, false)
        } else {
            progressView.visibility = View.GONE
        }
        root.setOnClickListener { onClick() }
        bindMore(root, root.findViewById(R.id.row_more), onMore)
    }

    /** Fills one row view (item_row.xml) for a track. Used by lists and by the queue. */
    fun bindTrack(root: View, item: TrackItem, currentId: String?) {
        val t = item.track
        val playing = currentId != null && currentId == t.id
        fill(
            root, t.title, item.subtitle, 0, false, playing, item.number,
            if (t.kind != Kind.MUSIC) item.progress else -1, item.onClick, item.onMore,
        )
    }

    fun entityIcon(icon: Int, round: Boolean): Int = when {
        icon != 0 -> icon
        round -> R.drawable.ic_person
        else -> R.drawable.ic_album
    }

    fun bindEntity(root: View, item: EntityItem) =
        fill(root, item.title, item.subtitle, entityIcon(item.icon, item.round), item.brand, false, 0, -1, item.onClick, item.onMore)

    fun bindCard(root: View, card: Card, currentId: String?) {
        val playing = currentId != null && card.id.isNotEmpty() && card.id == currentId
        // A card with an id is a single track: no icon, like track rows
        val iconRes = if (card.id.isNotEmpty() && card.icon == 0 && !card.round) 0 else entityIcon(card.icon, card.round)
        fill(root, card.title, card.subtitle, iconRes, card.brand, playing, 0, card.progress, card.onClick, card.onMore)
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
}

class ItemsAdapter(private val currentId: () -> String?) : RecyclerView.Adapter<ItemsAdapter.Holder>() {
    private var ids = LongArray(0)
    private var shown: List<Item> = emptyList()
    private val expanded = HashSet<String>()
    private var recycler: RecyclerView? = null

    /** What the screen wants to show. Shelves are turned into short lists here. */
    var items: List<Item> = emptyList()
        set(value) {
            field = value
            rebuild()
        }

    init {
        setHasStableIds(true)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    private fun rebuild() {
        val out = ArrayList<Item>(items.size + 16)
        for (item in items) {
            if (item !is ShelfItem) {
                out += item
                continue
            }
            if (item.cards.isEmpty()) continue
            if (item.cards.all { it.round }) {
                out += PillsItem(item.key, item.cards)
                continue
            }
            val open = item.key in expanded || item.cards.size <= SHELF_ROWS + 1
            val visible = if (open) item.cards else item.cards.take(SHELF_ROWS)
            visible.forEach { out += CardRowItem(item.key, it) }
            if (!open) out += MoreItem(item.key, item.cards.size - SHELF_ROWS)
        }
        shown = out
        // Stable ids keep keypad focus in place when the list is redrawn.
        val seen = HashMap<String, Int>()
        ids = LongArray(out.size) { i ->
            val base = keyOf(out[i])
            val n = seen.merge(base, 1) { a, b -> a + b } ?: 1
            ("$base#$n").hashCode().toLong()
        }
        notifyDataSetChanged()
    }

    private fun expand(key: String, position: Int) {
        expanded += key
        rebuild()
        // The "show more" line turns into the next row: keep the keypad there
        recycler?.post {
            recycler?.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }

    private fun keyOf(item: Item): String = when (item) {
        is TitleItem -> "title:${item.text}"
        is HeaderItem -> "header:${item.text}"
        is SectionItem -> "section:${item.text}"
        is TrackItem -> "track:${item.track.id}"
        is EntityItem -> "entity:${item.title}:${item.subtitle}"
        is MessageItem -> "message:${item.text.take(40)}"
        is ButtonsItem -> "buttons:${item.buttons.joinToString { it.first }}"
        is LoadingItem -> "loading"
        is ShelfItem -> "shelf:${item.key}"
        is CardRowItem -> "card:${item.shelf}:${item.card.id.ifEmpty { item.card.title + item.card.subtitle }}"
        is PillsItem -> "pills:${item.key}"
        is MoreItem -> "more:${item.key}"
        is CategoryItem -> "category:${item.title}"
        is ChipsItem -> "chips:${item.options.joinToString()}"
        is HeroItem -> "hero:${item.title}"
        is LogoItem -> "logo"
    }

    override fun getItemId(position: Int): Long = ids.getOrElse(position) { position.toLong() }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = when (shown[position]) {
        is TitleItem -> 0
        is HeaderItem -> 1
        is TrackItem, is EntityItem, is CardRowItem -> 2
        is MessageItem -> 4
        is ButtonsItem -> 5
        is LoadingItem -> 6
        is PillsItem -> 7
        is MoreItem -> 8
        is CategoryItem -> 9
        is ChipsItem -> 10
        is HeroItem -> 11
        is LogoItem -> 12
        is SectionItem -> 13
        is ShelfItem -> 6
    }

    /** Two-column grid: search categories take one column, everything else the full width. */
    fun spanSize(position: Int): Int = when (shown.getOrNull(position)) {
        is CategoryItem -> 1
        else -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = when (viewType) {
            0 -> R.layout.item_title
            1 -> R.layout.item_header
            2 -> R.layout.item_row
            4 -> R.layout.item_message
            5 -> R.layout.item_buttons
            6 -> R.layout.item_loading
            7 -> R.layout.item_pills
            8 -> R.layout.item_more
            9 -> R.layout.item_category
            10 -> R.layout.item_chips
            11 -> R.layout.item_hero
            13 -> R.layout.item_section
            else -> R.layout.item_logo
        }
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val v = holder.itemView
        val context = v.context
        when (val item = shown[position]) {
            is TitleItem -> {
                v.findViewById<TextView>(R.id.title_text).text = item.text
                val sub = v.findViewById<TextView>(R.id.title_sub)
                sub.text = item.subtitle
                sub.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
            }
            is SectionItem -> {
                v.findViewById<TextView>(R.id.section_text).text = item.text
                v.findViewById<View>(R.id.section_line).visibility = if (item.first) View.GONE else View.VISIBLE
            }
            is HeaderItem -> {
                val text = v.findViewById<TextView>(R.id.header_text)
                text.text = item.text
                if (item.sub) {
                    text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.sub_header_text))
                    text.setTextColor(context.color(R.color.q_on_surface_variant))
                    v.setPadding(v.paddingLeft, context.dp(10), v.paddingRight, v.paddingBottom)
                } else {
                    text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.header_text))
                    text.setTextColor(context.color(R.color.q_on_surface))
                    v.setPadding(v.paddingLeft, context.resources.getDimensionPixelSize(R.dimen.header_pad_top), v.paddingRight, v.paddingBottom)
                }
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
            is CardRowItem -> Rows.bindCard(v, item.card, currentId())
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
            is LoadingItem, is ShelfItem -> Unit
            is PillsItem -> {
                val group = v.findViewById<ChipGroup>(R.id.pills)
                group.removeAllViews()
                val inflater = LayoutInflater.from(context)
                for (card in item.cards) {
                    val chip = inflater.inflate(R.layout.chip_pill, group, false) as Chip
                    chip.text = card.title
                    chip.setOnClickListener { card.onClick() }
                    val onMore = card.onMore
                    if (onMore != null) chip.setOnLongClickListener { onMore(); true }
                    group.addView(chip)
                }
            }
            is MoreItem -> {
                val t = v as TextView
                t.text = "הצג עוד ${item.hidden}"
                t.setOnClickListener { expand(item.key, holder.bindingAdapterPosition) }
            }
            is CategoryItem -> {
                val t = v.findViewById<TextView>(R.id.category)
                t.text = item.title
                t.background = Accents.category(context, item.title)
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
                val iconView = v.findViewById<ImageView>(R.id.hero_icon)
                iconView.setImageResource(Rows.entityIcon(item.icon, item.round))
                val kind = v.findViewById<TextView>(R.id.hero_kind)
                kind.text = item.kind
                kind.visibility = if (item.kind.isBlank()) View.GONE else View.VISIBLE
                v.findViewById<TextView>(R.id.hero_title).text = item.title
                val sub = v.findViewById<TextView>(R.id.hero_sub)
                sub.text = item.subtitle
                sub.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
                val onSub = item.onSubtitle
                sub.setTextColor(context.color(if (onSub != null) R.color.q_primary else R.color.q_on_surface_variant))
                sub.isClickable = onSub != null
                sub.isFocusable = onSub != null
                if (onSub != null) sub.setOnClickListener { onSub() } else sub.setOnClickListener(null)
                val box = v.findViewById<ViewGroup>(R.id.hero_buttons)
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

    override fun getItemCount(): Int = shown.size

    companion object {
        /** Rows shown per group before "show more". */
        const val SHELF_ROWS = 4
    }
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
