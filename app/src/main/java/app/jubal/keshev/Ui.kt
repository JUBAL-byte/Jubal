package app.jubal.keshev

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.themeAttr(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.resourceId
}

fun Context.vbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.label(text: String = "", sizeSp: Float = 15f, bold: Boolean = false, secondary: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (secondary) {
            val colors = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            setTextColor(colors.getColorStateList(0))
            colors.recycle()
        }
    }

fun Context.button(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun LinearLayout.addPadded(view: View, horizontalDp: Int = 12, verticalDp: Int = 4): View {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.setMargins(context.dp(horizontalDp), context.dp(verticalDp), context.dp(horizontalDp), context.dp(verticalDp))
    addView(view, lp)
    return view
}

fun LinearLayout.addWeighted(view: View, weight: Float = 1f): View {
    addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
    return view
}

fun LinearLayout.addFill(view: View): View {
    addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    return view
}

fun Context.list(adapter: RowAdapter): RecyclerView = RecyclerView(this).apply {
    layoutManager = LinearLayoutManager(this@list)
    this.adapter = adapter
    isFocusable = false
    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
}

/**
 * Removes "web search", "open link", "translate" and similar entries from the
 * text-selection menu, so nothing in the app can open a browser.
 */
fun EditText.withoutWebActions() {
    val allowed = setOf(
        android.R.id.cut, android.R.id.copy, android.R.id.paste,
        android.R.id.selectAll, android.R.id.pasteAsPlainText,
    )
    val callback = object : ActionMode.Callback {
        private fun strip(menu: Menu) {
            for (i in menu.size() - 1 downTo 0) {
                if (i >= menu.size()) continue
                val item = menu.getItem(i)
                if (item.itemId !in allowed) menu.removeItem(item.itemId)
            }
            // Any leftovers that share an id (removeItem only removes the first match)
            var guard = 0
            while (guard++ < 50) {
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

/** A text-only list row: title, a smaller line under it, and an optional status. */
class Row(
    val title: String,
    val subtitle: String = "",
    val meta: String = "",
    val onClick: () -> Unit,
)

class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(val root: LinearLayout, val title: TextView, val subtitle: TextView, val meta: TextView) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val root = c.vbox().apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
            isFocusable = true
            isClickable = true
            setBackgroundResource(c.themeAttr(android.R.attr.selectableItemBackground))
        }
        val title = c.label(sizeSp = 16f, bold = true)
        val subtitle = c.label(sizeSp = 14f, secondary = true)
        val meta = c.label(sizeSp = 13f, secondary = true)
        root.addView(title)
        root.addView(subtitle)
        root.addView(meta)
        return Holder(root, title, subtitle, meta)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
        holder.meta.text = row.meta
        holder.meta.visibility = if (row.meta.isBlank()) View.GONE else View.VISIBLE
        holder.root.setOnClickListener { row.onClick() }
    }

    override fun getItemCount(): Int = rows.size
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

    fun date(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(ms))

    fun join(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(" · ")
}
