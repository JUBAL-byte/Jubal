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
    // Slow, overlapping waves look calm and smooth even on a small phone
    private val speed = floatArrayOf(2.1f, 2.9f, 2.4f, 3.3f, 2.2f, 3.1f, 2.6f)
    private val phase = floatArrayOf(0.0f, 1.3f, 2.1f, 0.7f, 2.9f, 1.8f, 0.4f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val start = SystemClock.uptimeMillis()

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
        // Time since this view was made (a float of the phone's whole uptime is too coarse and stutters)
        val t = ((SystemClock.uptimeMillis() - start) % 3_600_000L) / 1000f
        for (i in 0 until n) {
            var f = base[i]
            if (playing) {
                val wave = 0.6f * sin(t * speed[i] + phase[i]) + 0.4f * sin(t * speed[i] * 0.53f + phase[i] * 2f)
                f *= 0.62f + 0.38f * (0.5f + 0.5f * wave)
            }
            val bh = max(bar, h * f)
            val left = paddingLeft + i * (bar + gap)
            val top = paddingTop + (h - bh) / 2f
            rect.set(left, top, left + bar, top + bh)
            paint.color = color
            paint.alpha = (255 * shade[i]).toInt()
            canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint)
        }
        // Draw again on the next screen frame, only while visible and playing
        if (playing && isShown) postInvalidateOnAnimation()
    }
}
