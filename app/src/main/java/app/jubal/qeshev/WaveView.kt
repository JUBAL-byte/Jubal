package app.jubal.qeshev

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/**
 * The JUBAL mark: seven rounded bars. It stands where a cover picture would be,
 * and moves gently while something is playing.
 */
class WaveView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    // Heights and shades measured from the original logo
    private val base = floatArrayOf(119f, 217f, 315f, 175f, 273f, 147f, 91f).map { it / 315f }.toFloatArray()
    private val shade = floatArrayOf(0.55f, 0.75f, 0.92f, 1f, 0.92f, 0.75f, 0.55f)
    private val speed = floatArrayOf(5.1f, 7.3f, 6.2f, 8.4f, 5.7f, 7.9f, 6.6f)
    private val phase = floatArrayOf(0.0f, 1.3f, 2.1f, 0.7f, 2.9f, 1.8f, 0.4f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var color: Int = context.color(R.color.q_primary)
        set(value) {
            field = value
            invalidate()
        }

    var playing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = base.size
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return
        // In the logo the gap between bars is a little narrower than a bar
        val bar = w / (n + (n - 1) * 0.926f)
        val gap = bar * 0.926f
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            var f = base[i]
            if (playing) f *= 0.5f + 0.5f * (0.5f + 0.5f * sin(t * speed[i] + phase[i]))
            val bh = max(bar, h * f)
            val left = paddingLeft + i * (bar + gap)
            val top = paddingTop + (h - bh) / 2f
            rect.set(left, top, left + bar, top + bh)
            paint.color = color
            paint.alpha = (255 * shade[i]).toInt()
            canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint)
        }
        if (playing && isShown) postInvalidateDelayed(60)
    }
}
