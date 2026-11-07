package gr.vassbak.standby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Το κουμπάκι κάτω αριστερά: πράσινη κουκκίδα = οθόνη πάντα ανοιχτή, γκρι = κλείνει μόνη της,
 * αχνό λουκέτο = κλειδωμένη περιστροφή (και η οθόνη μένει ανοιχτή).
 */
class AodButtonView(context: Context, private val green: Int) : View(context) {

    var mode = MODE_ON
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lock = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255) }
    private val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(120, 255, 255, 255); strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        if (mode == MODE_LOCK) {
            // Λουκέτο: σώμα κάτω, κρίκος πάνω
            val bw = s * 0.62f; val bh = s * 0.44f
            val body = RectF(cx - bw / 2, cy - bh * 0.05f, cx + bw / 2, cy + bh * 0.95f)
            c.drawRoundRect(body, s * 0.1f, s * 0.1f, lock)
            shackle.strokeWidth = s * 0.11f
            val r = bw * 0.32f
            c.drawArc(RectF(cx - r, body.top - r * 1.5f, cx + r, body.top + r * 0.5f), 180f, 180f, false, shackle)
            c.drawLine(cx - r, body.top - r * 0.5f, cx - r, body.top, shackle)
            c.drawLine(cx + r, body.top - r * 0.5f, cx + r, body.top, shackle)
            return
        }
        val on = mode == MODE_ON
        val r = s / 2f * 0.9f
        fill.color = if (on) green else Color.parseColor("#5A5A5A")
        c.drawCircle(cx, cy, r, fill)
        ring.strokeWidth = s * 0.08f
        ring.color = if (on) Color.argb(0x66, Color.red(green), Color.green(green), Color.blue(green)) else Color.argb(0x33, 255, 255, 255)
        c.drawCircle(cx, cy, r, ring)
    }

    companion object {
        const val MODE_ON = 0
        const val MODE_OFF = 1
        const val MODE_LOCK = 2
    }
}
