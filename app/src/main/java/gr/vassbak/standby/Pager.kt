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

/** Κάθετες σελίδες με swipe πάνω/κάτω. Κάθε παιδί πιάνει όλη την οθόνη. */
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
    private var lastY = 0f
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
        for (i in 0 until childCount) getChildAt(i).layout(0, i * h, w, (i + 1) * h)
        if (changed) scrollTo(0, current * h)
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
                downX = ev.x; downY = ev.y; lastY = ev.y
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx)) {
                    dragging = true
                    lastY = ev.y
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
                downX = ev.x; downY = ev.y; lastY = ev.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.y - downY) > slop) dragging = true
                if (dragging) {
                    val maxY = (childCount - 1) * height
                    val ny = (scrollY + (lastY - ev.y)).roundToInt().coerceIn(0, maxY)
                    scrollTo(0, ny)
                    lastY = ev.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    var target = (scrollY.toFloat() / height).roundToInt()
                    if (abs(vy) > minFling) {
                        target = if (vy < 0) (scrollY / height) + 1 else scrollY / height
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
        val target = p * height
        anim?.cancel()
        if (!animate || height == 0) {
            scrollTo(0, target)
        } else {
            anim = ValueAnimator.ofInt(scrollY, target).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
                addUpdateListener { scrollTo(0, it.animatedValue as Int) }
                start()
            }
        }
        if (changed) onPageChanged(p)
    }
}
