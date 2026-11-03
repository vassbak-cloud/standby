package gr.vassbak.standby

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Κρατάει ένα μόνο παιδί. Αν η οθόνη είναι όρθια και ο χρήστης έχει ζητήσει
 * «πάντα οριζόντια», περιστρέφει το περιεχόμενο κατά [angle] μοίρες, ώστε να
 * φαίνεται οριζόντιο ακόμα κι όταν η αυτόματη περιστροφή είναι κλειστή.
 */
class RotatedLayout(context: Context, private val angle: Int) : ViewGroup(context) {

    private var rotate = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        rotate = angle != 0 && h > w
        val child = getChildAt(0)
        if (child != null) {
            val cw = if (rotate) h else w
            val ch = if (rotate) w else h
            child.measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child: View = getChildAt(0) ?: return
        val w = r - l
        val h = b - t
        if (rotate) {
            val left = (w - h) / 2
            val top = (h - w) / 2
            child.layout(left, top, left + h, top + w)
            child.rotation = angle.toFloat()
        } else {
            child.layout(0, 0, w, h)
            child.rotation = 0f
        }
    }
}
