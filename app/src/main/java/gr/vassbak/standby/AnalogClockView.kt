package gr.vassbak.standby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Αναλογικό ρολόι: γραμμές ωρών, δείκτες ώρας/λεπτών και πορτοκαλί δευτερολεπτοδείκτης. */
class AnalogClockView(context: Context, private val accent: Int) : View(context) {

    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#33FFFFFF") }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val sec = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f * 0.94f
        val cx = width / 2f
        val cy = height / 2f
        val u = r / 100f   // μονάδα: 1% της ακτίνας

        face.strokeWidth = 1.5f * u
        c.drawCircle(cx, cy, r, face)

        // Γραμμές: 12 ώρες (οι 12/3/6/9 πιο έντονες) και λεπτά
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val major = i % 15 == 0
            val hour = i % 5 == 0
            val inner = r * when { major -> 0.80f; hour -> 0.85f; else -> 0.93f }
            tick.strokeWidth = u * when { major -> 4f; hour -> 2.5f; else -> 1f }
            tick.alpha = if (hour) 255 else 110
            c.drawLine(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(),
                cx + r * 0.98f * cos(a).toFloat(), cy + r * 0.98f * sin(a).toFloat(), tick)
        }

        val now = Calendar.getInstance()
        val s = now.get(Calendar.SECOND)
        val m = now.get(Calendar.MINUTE) + s / 60f
        val h = now.get(Calendar.HOUR) + m / 60f

        fun drawHand(p: Paint, angleDeg: Float, len: Float, tail: Float, w: Float) {
            val a = Math.toRadians(angleDeg - 90.0)
            p.strokeWidth = w
            c.drawLine(cx - tail * cos(a).toFloat(), cy - tail * sin(a).toFloat(),
                cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat(), p)
        }
        drawHand(hand, h * 30f, r * 0.52f, r * 0.08f, 7f * u)
        drawHand(hand, m * 6f, r * 0.80f, r * 0.10f, 4.5f * u)
        drawHand(sec, s * 6f, r * 0.86f, r * 0.16f, 1.6f * u)
        c.drawCircle(cx, cy, 5f * u, hand)
        c.drawCircle(cx, cy, 2.5f * u, sec)
    }
}
