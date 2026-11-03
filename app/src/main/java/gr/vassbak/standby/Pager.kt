package gr.vassbak.standby

import android.animation.ValueAnimator
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/** Απλές οριζόντιες σελίδες με swipe. Κάθε παιδί πιάνει όλη την οθόνη. */
class Pager(context: Context) : ViewGroup(context) {

    var current = 0
        private set
    var onPageChanged: (Int) -> Unit = {}
    var onDoubleTap: () -> Unit = {}
    var onInteraction: () -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 3
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap()
            return true
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, h)
        if (changed) scrollTo(current * w, 0)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop && abs(dx) > abs(dy)) {
                    dragging = true
                    lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downX = ev.x; downY = ev.y; lastX = ev.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > slop) dragging = true
                if (dragging) {
                    val maxX = (childCount - 1) * width
                    val nx = (scrollX + (lastX - ev.x)).roundToInt().coerceIn(0, maxX)
                    scrollTo(nx, 0)
                    lastX = ev.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    var target = (scrollX.toFloat() / width).roundToInt()
                    if (abs(vx) > minFling) {
                        target = if (vx < 0) (scrollX / width) + 1 else scrollX / width
                    }
                    goTo(target)
                }
                dragging = false
                tracker?.recycle(); tracker = null
            }
        }
        return true
    }

    fun goTo(page: Int, animate: Boolean = true) {
        val p = page.coerceIn(0, maxOf(0, childCount - 1))
        val changed = p != current
        current = p
        val target = p * width
        anim?.cancel()
        if (!animate || width == 0) {
            scrollTo(target, 0)
        } else {
            anim = ValueAnimator.ofInt(scrollX, target).apply {
                duration = 280
                interpolator = DecelerateInterpolator()
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }
        if (changed) onPageChanged(p)
    }
}
