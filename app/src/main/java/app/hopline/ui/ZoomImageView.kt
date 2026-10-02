package app.hopline.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A photo you can pinch, double-tap and drag, like any gallery: it starts fitted to the screen,
 * zooms up to 5x, pans only while zoomed, and never drifts off-screen. At normal size a vertical
 * drag pulls the photo away, and past a point (or with a flick) closes the viewer.
 */
class ZoomImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : AppCompatImageView(ctx, attrs) {
    /** A single tap (or an accessibility click). */
    var onTap: (() -> Unit)? = null
    /** How far a pull-to-close has gone: 0 = resting, 1 = far enough to close on release. */
    var onDrag: ((Float) -> Unit)? = null
    /** Pulled away far enough. Leave null to switch pull-to-close off. */
    var onDismiss: (() -> Unit)? = null

    // ImageView's constructor calls setImageDrawable before our fields exist; [ready] guards that.
    private var ready = false
    private val fit = Matrix()
    private val user = Matrix()
    private val shown = Matrix()
    private val values = FloatArray(9)
    private val rect = RectF()
    private val scroller = OverScroller(ctx)
    private var zoomAnim: ValueAnimator? = null
    private var settleAnim: ValueAnimator? = null
    private var flingX = 0
    private var flingY = 0
    private var dragging = false
    private var flungAway = false
    private var downRawX = 0f
    private var downRawY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val flingAwaySpeed = 1200f * ctx.resources.displayMetrics.density

    private val zoom: Float get() { user.getValues(values); return values[Matrix.MSCALE_X] }
    private val zoomed: Boolean get() = zoom > 1.01f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (dragging) return false
            stopMotion()
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
    }).apply { isQuickScaleEnabled = false }   // double-tap is "zoom in / back out", not double-tap-and-drag

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (dragging) return false
            animateZoomTo(if (zoomed) 1f else DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaler.isInProgress || e2.pointerCount > 1) return false
            if (zoomed) { user.postTranslate(-dx, -dy); clamp(); apply(); return true }
            // Screen coordinates: the view itself moves while it is being pulled.
            val ty = e2.rawY - downRawY
            val tx = e2.rawX - downRawX
            if (!dragging && onDismiss != null && abs(ty) > slop && abs(ty) > abs(tx) * 1.5f) dragging = true
            if (dragging) { translationY = ty; onDrag?.invoke(pullProgress()) }
            return dragging
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaler.isInProgress) return false
            if (dragging) {
                if (abs(vy) > flingAwaySpeed && sign(vy) == sign(translationY)) flungAway = true
                return true
            }
            if (zoomed) { fling(vx, vy); return true }
            return false
        }
    })

    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val x = scroller.currX; val y = scroller.currY
            user.postTranslate((flingX - x).toFloat(), (flingY - y).toFloat())
            flingX = x; flingY = y
            clamp(); apply()
            postOnAnimation(this)
        }
    }

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        ready = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        settleAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")   // a confirmed single tap calls performClick() (see the gesture listener)
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downRawX = ev.rawX; downRawY = ev.rawY; flungAway = false
            settleAnim?.cancel()
        }
        scaler.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                dragging = false
                val away = ev.actionMasked == MotionEvent.ACTION_UP && (flungAway || pullProgress() >= 1f)
                if (away && onDismiss != null) onDismiss?.invoke() else settleBack()
            }
        }
        // Keep any scrolling parent from stealing a pinch, a pan or a pull.
        parent?.requestDisallowInterceptTouchEvent(zoomed || dragging || ev.pointerCount > 1)
        return true
    }

    /**
     * Back to "fitted, centred", e.g. when the full photo replaces its thumbnail. A pull-to-close
     * in progress is left alone, so the photo doesn't jump out from under the finger.
     */
    fun reset() {
        if (!ready) return
        stopMotion()
        user.reset()
        computeFit()
        apply()
    }

    private fun stopMotion() {
        zoomAnim?.cancel(); zoomAnim = null
        scroller.forceFinished(true)
        removeCallbacks(flingStep)
    }

    private fun computeFit() {
        fit.reset()
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat(); val vh = height.toFloat()
        if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) return
        val s = minOf(vw / dw, vh / dh)
        fit.setScale(s, s)
        fit.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f)
    }

    private fun apply() {
        shown.set(fit); shown.postConcat(user)
        imageMatrix = shown
    }

    /** Where the picture sits on screen right now (null when there's nothing to show). */
    private fun shownRect(): RectF? {
        val d = drawable ?: return null
        if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return null
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        shown.set(fit); shown.postConcat(user)
        shown.mapRect(rect)
        return rect
    }

    /** Fill the screen where the picture is big enough; centre it where it isn't. Never let it wander off. */
    private fun clamp() {
        val r = shownRect() ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val dx = when {
            r.width() <= vw -> (vw - r.width()) / 2f - r.left
            r.left > 0f -> -r.left
            r.right < vw -> vw - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= vh -> (vh - r.height()) / 2f - r.top
            r.top > 0f -> -r.top
            r.bottom < vh -> vh - r.bottom
            else -> 0f
        }
        if (dx != 0f || dy != 0f) user.postTranslate(dx, dy)
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val current = zoom
        val target = (current * factor).coerceIn(1f, MAX_ZOOM)
        if (target == current) return
        val f = target / current
        user.postScale(f, f, fx, fy)
        clamp(); apply()
    }

    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        stopMotion()
        zoomAnim = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomBy((it.animatedValue as Float) / zoom, fx, fy) }
            start()
        }
    }

    private fun fling(vx: Float, vy: Float) {
        val r = shownRect() ?: return
        val vw = width; val vh = height
        val startX = (-r.left).roundToInt(); val startY = (-r.top).roundToInt()
        // An axis that already fits the screen doesn't move.
        val maxX = if (r.width() > vw) (r.width() - vw).roundToInt() else startX
        val minX = if (r.width() > vw) 0 else startX
        val maxY = if (r.height() > vh) (r.height() - vh).roundToInt() else startY
        val minY = if (r.height() > vh) 0 else startY
        stopMotion()
        scroller.fling(startX, startY, (-vx).roundToInt(), (-vy).roundToInt(), minX, maxX, minY, maxY)
        flingX = startX; flingY = startY
        postOnAnimation(flingStep)
    }

    private fun pullProgress(): Float = if (height <= 0) 0f else (abs(translationY) / (height * DISMISS_FRACTION)).coerceIn(0f, 1f)

    private fun settleBack() {
        settleAnim?.cancel()
        settleAnim = ValueAnimator.ofFloat(translationY, 0f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { translationY = it.animatedValue as Float; onDrag?.invoke(pullProgress()) }
            start()
        }
    }

    private companion object {
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
        /** Pulled this share of the screen height, the photo closes on release. */
        const val DISMISS_FRACTION = 0.2f
    }
}
