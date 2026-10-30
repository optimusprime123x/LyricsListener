package dev.optimus.lyricslistener

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
    private val placement = LyricsBubblePlacement()
    private var dismissTarget: LinearLayout? = null
    private var dismissCircle: FrameLayout? = null
    private var dragging = false
    private var hoveringDismiss = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialCenterX = 0f
    private var initialCenterY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val fitRunnable = Runnable { fitToScreen() }
    private var transition: ValueAnimator? = null
    private var transitioningLyrics: Pair<View, WindowManager.LayoutParams>? = null
    private var lyricsFlagsBeforeTransition = 0
    private data class ExpansionPresentation(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val background: GradientDrawable?,
        val cornerRadius: Float,
        val content: View?,
        val contentAlpha: Float
    )
    private var expansionPresentation: ExpansionPresentation? = null
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
        }.also { view.addView(it, FrameLayout.LayoutParams(dp(30), dp(34), Gravity.CENTER)) }

        val bounds = boundsFor(view)
        val size = bubbleSize(bounds)
        val position = placement.positionFor(bounds, size, centerY)
        placement.remember(position.first, position.second)
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
        if (rememberPosition && !isAnimating) params?.let { placement.remember(it.x, it.y) }
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

    fun dismiss() {
        hide(rememberPosition = false)
        placement.forget()
    }

    fun onConfigurationChanged() {
        finishAnimation()
        hideDismissTarget()
        val view = bubble ?: return
        val layoutParams = params ?: return
        val bounds = boundsFor(view)
        val size = bubbleSize(bounds)
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
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                initialCenterX = location[0] + view.width / 2f
                initialCenterY = location[1] + view.height / 2f
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
                    moveDraggedBubble(view, layoutParams, event)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dismiss = dragging && moveDraggedBubble(view, layoutParams, event)
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

    private fun moveDraggedBubble(view: View, layoutParams: WindowManager.LayoutParams, event: MotionEvent): Boolean {
        val bounds = boundsFor(view)
        val position = bounds.constrainPosition(
            initialX + (event.rawX - initialTouchX).roundToInt(),
            initialY + (event.rawY - initialTouchY).roundToInt(),
            layoutParams.width, layoutParams.height
        )
        val pull = dismissPull(
            initialCenterX + position.first - initialX,
            initialCenterY + position.second - initialY
        )
        val attractedPosition = bounds.constrainPosition(
            position.first + pull.x.roundToInt(), position.second + pull.y.roundToInt(),
            layoutParams.width, layoutParams.height
        )
        layoutParams.x = attractedPosition.first
        layoutParams.y = attractedPosition.second
        updateWindow(view, layoutParams)
        updateDismissHover(pull.captured)
        return pull.captured
    }

    private fun snapToEdge(view: View, layoutParams: WindowManager.LayoutParams) {
        val bounds = boundsFor(view)
        val edge = if (layoutParams.x < 0) Int.MIN_VALUE else Int.MAX_VALUE
        val position = bounds.constrainPosition(edge, layoutParams.y, layoutParams.width, layoutParams.height)
        layoutParams.x = position.first
        layoutParams.y = position.second
        placement.remember(position.first, position.second)
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
            background = dismissBackground(false)
            elevation = dp(8).toFloat()
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_round_close_24)
                setColorFilter(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }.also { target.addView(it, LinearLayout.LayoutParams(dp(64), dp(64))) }
        target.addView(TextView(context).apply {
            text = "Dismiss"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(0xDD202024.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }, LinearLayout.LayoutParams(-2, dp(24)).apply { topMargin = dp(8) })
        val targetParams = overlayParams(dp(128), dp(104)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(12)
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

    private fun dismissPull(x: Float, y: Float): LyricsBubbleMagnet.Pull {
        val target = dismissCircle ?: return LyricsBubbleMagnet.Pull()
        val container = dismissTarget ?: return LyricsBubbleMagnet.Pull()
        if (!target.isAttachedToWindow || target.width == 0) return LyricsBubbleMagnet.Pull()
        val location = IntArray(2)
        // Use the unscaled container so hover animations cannot shift the magnetic centre.
        container.getLocationOnScreen(location)
        val centerX = location[0] + target.left + target.width / 2f
        val centerY = location[1] + target.top + target.height / 2f
        // Include the bottom edge of the usable screen in the capture area.
        val captureRadius = target.width / 2f + dp(48)
        return LyricsBubbleMagnet.pull(x, y, centerX, centerY, captureRadius, captureRadius + dp(32))
    }

    private fun dismissBackground(hovering: Boolean) = circle(
        if (hovering) Color.rgb(235, 45, 65) else Color.rgb(180, 38, 55)
    ).apply { setStroke(dp(2), 0xDDFFFFFF.toInt()) }

    private fun updateDismissHover(hovering: Boolean) {
        if (hoveringDismiss == hovering) return
        hoveringDismiss = hovering
        dismissCircle?.apply {
            background = dismissBackground(hovering)
            animate().scaleX(if (hovering) 1.12f else 1f)
                .scaleY(if (hovering) 1.12f else 1f).setDuration(120L).start()
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
        placement.remember(source.x, source.y)
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
        val bounds = boundsFor(bubble ?: return false)
        val size = bubbleSize(bounds)
        val position = placement.positionFor(bounds, size, source.y)
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
        val bubbleRadius = minOf(if (expanding) source.width else target.width, dp(60)) / 2f
        val lyricsBackground = if (expanding) lyrics.background as? GradientDrawable else null
        val lyricsContent = lyrics.findViewById<View>(R.id.lyricsOverlayContent)
        if (expanding) {
            expansionPresentation = ExpansionPresentation(
                lyricsParams.width, lyricsParams.height, lyricsParams.x, lyricsParams.y,
                lyricsBackground, windowRadius, lyricsContent, lyricsContent?.alpha ?: 1f
            )
            // Grow the real, already-themed lyrics surface. Blending two translucent
            // windows changes their combined opacity even when their fades add up to one.
            view.alpha = 0f
            lyricsParams.alpha = 1f
            lyricsContent?.alpha = 0f
        }
        val morphView = if (expanding) lyrics else view
        val morphParams = if (expanding) lyricsParams else layoutParams
        val background = (lyricsBackground ?: GradientDrawable().apply {
            setColor(Color.rgb(40, 40, 44))
        }).apply {
            cornerRadius = if (expanding) bubbleRadius else windowRadius
        }
        morphView.background = background
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
                morphParams.width = interpolate(source.width, target.width).coerceAtLeast(1)
                morphParams.height = interpolate(source.height, target.height).coerceAtLeast(1)
                val position = boundsFor(morphView).constrainPosition(
                    interpolate(source.x, target.x), interpolate(source.y, target.y),
                    morphParams.width, morphParams.height
                )
                morphParams.x = position.first
                morphParams.y = position.second
                background.cornerRadius = if (expanding) {
                    bubbleRadius + (windowRadius - bubbleRadius) * geometryProgress
                } else {
                    windowRadius + (bubbleRadius - windowRadius) * geometryProgress
                }
                note?.alpha = if (expanding) (1f - progress / 0.3f).coerceIn(0f, 1f)
                    else ((progress - 0.65f) / 0.35f).coerceIn(0f, 1f)
                if (expanding) {
                    // Reveal text after resizing, without fading the background or blur.
                    lyricsContent?.alpha = (expansionPresentation?.contentAlpha ?: 1f) *
                        ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f)
                } else {
                    view.alpha = (progress / 0.3f).coerceIn(0f, 1f)
                    if (!covered && progress >= 0.3f) {
                        covered = true
                        lyricsParams.alpha = 0f
                        updateWindow(lyrics, lyricsParams)
                    }
                }
                updateWindow(morphView, morphParams)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    transition = null
                    restoreLyricsPresentation()
                    if (!expanding) {
                        view.alpha = 1f
                        note?.alpha = 1f
                        view.background = circle(Color.rgb(40, 40, 44))
                    }
                    onFinished()
                }
            })
        }
        transition = animator
        animator.start()
    }

    private fun restoreLyricsPresentation() {
        transitioningLyrics?.let { (view, layoutParams) ->
            expansionPresentation?.let { presentation ->
                layoutParams.width = presentation.width
                layoutParams.height = presentation.height
                layoutParams.x = presentation.x
                layoutParams.y = presentation.y
                presentation.background?.cornerRadius = presentation.cornerRadius
                presentation.content?.alpha = presentation.contentAlpha
            }
            layoutParams.alpha = 1f
            layoutParams.flags = lyricsFlagsBeforeTransition
            updateWindow(view, layoutParams)
        }
        transitioningLyrics = null
        expansionPresentation = null
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

    private fun bubbleSize(bounds: LyricsWindowBounds) = dp(60).coerceAtMost(minOf(bounds.width, bounds.height))

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
