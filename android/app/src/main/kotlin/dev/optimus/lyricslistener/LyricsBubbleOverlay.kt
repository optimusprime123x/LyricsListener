package dev.optimus.lyricslistener

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Owns the small launcher window and its drag-to-dismiss target. All calls run on the main thread. */
internal class LyricsBubbleOverlay(
    private val context: Context,
    private val boundsFor: (View) -> LyricsWindowBounds,
    private val onOpen: () -> Unit,
    private val onDismiss: () -> Unit
) {
    data class WindowFrame(val x: Int, val y: Int, val width: Int, val height: Int)

    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubble: FrameLayout? = null
    private var artwork: ImageView? = null
    private var note: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastPosition: Pair<Int, Int>? = null
    private var dismissTarget: LinearLayout? = null
    private var dismissCircle: FrameLayout? = null
    private var dragging = false
    private var hoveringDismiss = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val fitRunnable = Runnable { fitToScreen() }
    private var transition: ValueAnimator? = null
    private var transitioningLyrics: Pair<View, WindowManager.LayoutParams>? = null
    private var lyricsFlagsBeforeTransition = 0
    val isAnimating: Boolean get() = transition != null
    val isShowing: Boolean get() = bubble?.parent != null

    fun show(bitmap: Bitmap?, centerY: Int = 0, initialFrame: WindowFrame? = null): Boolean {
        bubble?.takeIf { it.parent != null }?.let {
            updateArtwork(bitmap)
            return true
        }

        val view = FrameLayout(context).apply {
            background = circle(Color.rgb(40, 40, 44))
            clipToOutline = true
            elevation = dp(8).toFloat()
            contentDescription = "Open lyrics"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            alpha = if (initialFrame == null) 1f else 0f
            setOnClickListener { onOpen() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
                        onDismiss()
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        }
        artwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { view.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        view.addView(View(context).apply { setBackgroundColor(0x88000000.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        note = ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_note)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { view.addView(it, FrameLayout.LayoutParams(dp(32), dp(36), Gravity.CENTER)) }

        val bounds = boundsFor(view)
        val size = dp(64).coerceAtMost(minOf(bounds.width, bounds.height))
        val start = lastPosition ?: Pair((bounds.width - size) / 2, centerY)
        val position = bounds.constrainPosition(start.first, start.second, size, size)
        lastPosition = position
        val frame = initialFrame ?: WindowFrame(position.first, position.second, size, size)
        val layoutParams = overlayParams(frame.width, frame.height).apply {
            x = frame.x
            y = frame.y
        }
        bubble = view
        params = layoutParams
        updateArtwork(bitmap)
        view.setOnTouchListener { target, event -> onTouch(target, event) }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleFit() }
        view.setOnApplyWindowInsetsListener { _, insets -> scheduleFit(); insets }
        return try {
            manager.addView(view, layoutParams)
            scheduleFit()
            true
        } catch (e: Exception) {
            Log.e("LyricsBubbleOverlay", "Could not show lyrics bubble", e)
            hide()
            false
        }
    }

    fun updateArtwork(bitmap: Bitmap?) {
        artwork?.setImageBitmap(bitmap)
    }

    fun hide(rememberPosition: Boolean = true) {
        if (rememberPosition && !isAnimating) params?.let { lastPosition = Pair(it.x, it.y) }
        cancelTransition()
        dragging = false
        hideDismissTarget()
        bubble?.removeCallbacks(fitRunnable)
        removeWindow(bubble)
        bubble = null
        artwork = null
        note = null
        params = null
    }

    fun onConfigurationChanged() {
        finishAnimation()
        hideDismissTarget()
        val view = bubble ?: return
        val layoutParams = params ?: return
        val bounds = boundsFor(view)
        val size = dp(64).coerceAtMost(minOf(bounds.width, bounds.height))
        layoutParams.width = size
        layoutParams.height = size
        updateWindow(view, layoutParams)
        scheduleFit()
    }

    private fun scheduleFit() {
        bubble?.let { view ->
            if (view.isAttachedToWindow) {
                view.removeCallbacks(fitRunnable)
                view.post(fitRunnable)
            }
        }
    }

    private fun fitToScreen() {
        if (isAnimating) return
        val view = bubble ?: return
        val layoutParams = params ?: return
        if (!view.isAttachedToWindow) return
        val position = boundsFor(view).constrainPosition(
            layoutParams.x, layoutParams.y, layoutParams.width, layoutParams.height
        )
        if (layoutParams.x != position.first || layoutParams.y != position.second) {
            layoutParams.x = position.first
            layoutParams.y = position.second
            updateWindow(view, layoutParams)
        }
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        if (isAnimating) return true
        val layoutParams = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    showDismissTarget()
                }
                if (dragging) {
                    val position = boundsFor(view).constrainPosition(
                        initialX + dx.toInt(), initialY + dy.toInt(), layoutParams.width, layoutParams.height
                    )
                    layoutParams.x = position.first
                    layoutParams.y = position.second
                    updateWindow(view, layoutParams)
                    updateDismissHover(isOverDismissTarget(event.rawX, event.rawY))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dismiss = dragging && isOverDismissTarget(event.rawX, event.rawY)
                hideDismissTarget()
                if (dismiss) {
                    onDismiss()
                } else if (dragging) {
                    snapToEdge(view, layoutParams)
                } else {
                    view.performClick()
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hideDismissTarget()
                if (dragging) snapToEdge(view, layoutParams)
                dragging = false
                return true
            }
        }
        return false
    }

    private fun snapToEdge(view: View, layoutParams: WindowManager.LayoutParams) {
        val bounds = boundsFor(view)
        val edge = if (layoutParams.x < 0) Int.MIN_VALUE else Int.MAX_VALUE
        val position = bounds.constrainPosition(edge, layoutParams.y, layoutParams.width, layoutParams.height)
        layoutParams.x = position.first
        layoutParams.y = position.second
        lastPosition = position
        updateWindow(view, layoutParams)
    }

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val target = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        dismissCircle = FrameLayout(context).apply {
            background = circle(Color.rgb(65, 35, 40))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_round_close_24)
                setColorFilter(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }.also { target.addView(it, LinearLayout.LayoutParams(dp(64), dp(64))) }
        target.addView(TextView(context).apply {
            text = "Dismiss"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }, LinearLayout.LayoutParams(-1, dp(24)))
        val targetParams = overlayParams(dp(128), dp(96)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(16)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            manager.addView(target, targetParams)
            dismissTarget = target
        } catch (e: Exception) {
            dismissCircle = null
            Log.e("LyricsBubbleOverlay", "Could not show dismiss target", e)
        }
    }

    private fun isOverDismissTarget(x: Float, y: Float): Boolean {
        val target = dismissCircle ?: return false
        if (!target.isAttachedToWindow || target.width == 0) return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val centerX = location[0] + target.width / 2f
        val centerY = location[1] + target.height / 2f
        // Keep the target magnetic all the way to the bottom edge, including a finger-sized margin.
        return hypot(x - centerX, y - centerY) <= target.width / 2f + dp(48)
    }

    private fun updateDismissHover(hovering: Boolean) {
        if (hoveringDismiss == hovering) return
        hoveringDismiss = hovering
        dismissCircle?.apply {
            background = circle(if (hovering) Color.rgb(200, 40, 55) else Color.rgb(65, 35, 40))
            scaleX = if (hovering) 1.15f else 1f
            scaleY = scaleX
        }
    }

    private fun hideDismissTarget() {
        removeWindow(dismissTarget)
        dismissTarget = null
        dismissCircle = null
        hoveringDismiss = false
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            setFitInsetsIgnoringVisibility(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
    }

    private fun updateWindow(view: View, layoutParams: WindowManager.LayoutParams) {
        if (view.parent == null) return
        try {
            manager.updateViewLayout(view, layoutParams)
        } catch (e: Exception) {
            Log.e("LyricsBubbleOverlay", "Could not move overlay", e)
        }
    }

    private fun removeWindow(view: View?) {
        if (view?.parent == null) return
        try {
            manager.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.e("LyricsBubbleOverlay", "Could not remove overlay", e)
        }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), 0x55FFFFFF)
    }

    /** Morph the existing bubble into the already-measured, initially transparent lyrics window. */
    fun expandInto(view: View, layoutParams: WindowManager.LayoutParams, onFinished: () -> Unit): Boolean {
        val bubbleParams = params ?: return false
        if (!isShowing) return false
        val source = WindowFrame(bubbleParams.x, bubbleParams.y, bubbleParams.width, bubbleParams.height)
        val target = WindowFrame(layoutParams.x, layoutParams.y, view.width, view.height)
        lastPosition = Pair(source.x, source.y)
        animateMorph(view, layoutParams, source, target, expanding = true) {
            hide(rememberPosition = false)
            onFinished()
        }
        return true
    }

    /** Cover the lyrics with artwork, then shrink that same artwork into the launcher circle. */
    fun collapseFrom(bitmap: Bitmap?, view: View, layoutParams: WindowManager.LayoutParams, onFinished: () -> Unit): Boolean {
        val source = WindowFrame(layoutParams.x, layoutParams.y, view.width, view.height)
        if (!show(bitmap, source.y, initialFrame = source)) return false
        val position = lastPosition ?: return false
        val bounds = boundsFor(bubble ?: return false)
        val size = dp(64).coerceAtMost(minOf(bounds.width, bounds.height))
        val target = WindowFrame(position.first, position.second, size, size)
        animateMorph(view, layoutParams, source, target, expanding = false, onFinished)
        return true
    }

    fun finishAnimation() {
        transition?.end()
    }

    private fun animateMorph(
        lyrics: View,
        lyricsParams: WindowManager.LayoutParams,
        source: WindowFrame,
        target: WindowFrame,
        expanding: Boolean,
        onFinished: () -> Unit
    ) {
        val view = bubble ?: return
        val layoutParams = params ?: return
        hideDismissTarget()
        dragging = false
        transitioningLyrics = Pair(lyrics, lyricsParams)
        lyricsFlagsBeforeTransition = lyricsParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        lyricsParams.flags = lyricsFlagsBeforeTransition or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        updateWindow(lyrics, lyricsParams)
        val windowRadius = (lyrics.background as? GradientDrawable)?.cornerRadius ?: dp(28).toFloat()
        val bubbleRadius = minOf(if (expanding) source.width else target.width, dp(64)) / 2f
        val background = GradientDrawable().apply {
            setColor(Color.rgb(40, 40, 44))
            cornerRadius = if (expanding) bubbleRadius else windowRadius
        }
        view.background = background
        val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
        var covered = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val geometryProgress = easing.getInterpolation(
                    (if (expanding) progress / 0.7f else (progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
                )
                fun interpolate(from: Int, to: Int) = (from + (to - from) * geometryProgress).roundToInt()
                layoutParams.width = interpolate(source.width, target.width).coerceAtLeast(1)
                layoutParams.height = interpolate(source.height, target.height).coerceAtLeast(1)
                val position = boundsFor(view).constrainPosition(
                    interpolate(source.x, target.x), interpolate(source.y, target.y),
                    layoutParams.width, layoutParams.height
                )
                layoutParams.x = position.first
                layoutParams.y = position.second
                background.cornerRadius = if (expanding) {
                    bubbleRadius + (windowRadius - bubbleRadius) * geometryProgress
                } else {
                    windowRadius + (bubbleRadius - windowRadius) * geometryProgress
                }
                note?.alpha = if (expanding) (1f - progress / 0.3f).coerceIn(0f, 1f)
                    else ((progress - 0.65f) / 0.35f).coerceIn(0f, 1f)
                if (expanding) {
                    lyricsParams.alpha = ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f)
                    updateWindow(lyrics, lyricsParams)
                } else {
                    view.alpha = (progress / 0.3f).coerceIn(0f, 1f)
                    if (!covered && progress >= 0.3f) {
                        covered = true
                        lyricsParams.alpha = 0f
                        updateWindow(lyrics, lyricsParams)
                    }
                }
                updateWindow(view, layoutParams)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    transition = null
                    restoreLyricsPresentation()
                    view.alpha = 1f
                    note?.alpha = 1f
                    if (!expanding) view.background = circle(Color.rgb(40, 40, 44))
                    onFinished()
                }
            })
        }
        transition = animator
        animator.start()
    }

    private fun restoreLyricsPresentation() {
        transitioningLyrics?.let { (view, layoutParams) ->
            layoutParams.alpha = 1f
            layoutParams.flags = lyricsFlagsBeforeTransition
            updateWindow(view, layoutParams)
        }
        transitioningLyrics = null
    }

    private fun cancelTransition() {
        transition?.apply {
            removeAllListeners()
            removeAllUpdateListeners()
            cancel()
        }
        transition = null
        restoreLyricsPresentation()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
