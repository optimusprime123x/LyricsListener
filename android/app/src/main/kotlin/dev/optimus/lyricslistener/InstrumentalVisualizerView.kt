package dev.optimus.lyricslistener

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * A mock audio-equalizer visualizer view for instrumental/blank lyric lines.
 * Five bars oscillate at different speeds and phases to mimic a classic EQ display.
 * When not animating the bars rest at a static idle height.
 */
class InstrumentalVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val barRect = RectF()
    private var animationProgress = 0f
    private var isAnimating = false

    private val barCount = 5
    private val barWidthDp = 3f
    private val barGapDp = 2.5f
    private val barCornerDp = 1.5f
    private val idleHeightFraction = 0.18f
    private val minHeightFraction = 0.12f
    private val maxHeightFraction = 1.0f

    // Each bar has its own phase offset and speed multiplier for a realistic EQ look
    private val barPhaseOffsets = floatArrayOf(0f, 2.1f, 0.7f, 3.5f, 1.4f)
    private val barSpeedMultipliers = floatArrayOf(1.0f, 1.3f, 0.8f, 1.6f, 1.1f)

    private val density = context.resources.displayMetrics.density
    private val barWidthPx = barWidthDp * density
    private val barGapPx = barGapDp * density
    private val barCornerPx = barCornerDp * density

    private var animator: ValueAnimator? = null

    fun setBarColor(color: Int) {
        barPaint.color = color
        invalidate()
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        isAnimating = true
        animator = ValueAnimator.ofFloat(0f, (2 * kotlin.math.PI).toFloat()).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                animationProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
        isAnimating = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val totalBarsWidth = barCount * barWidthPx + (barCount - 1) * barGapPx
        val startX = (width - totalBarsWidth) / 2f
        val maxBarHeight = height.toFloat()

        for (i in 0 until barCount) {
            val heightFraction = if (isAnimating) {
                val phase = animationProgress * barSpeedMultipliers[i] + barPhaseOffsets[i]
                minHeightFraction +
                        (maxHeightFraction - minHeightFraction) * ((sin(phase.toDouble()) + 1.0) / 2.0).toFloat()
            } else {
                idleHeightFraction
            }

            val barHeight = maxBarHeight * heightFraction
            val left = startX + i * (barWidthPx + barGapPx)
            val top = maxBarHeight - barHeight  // Bars grow upward from bottom

            barRect.set(left, top, left + barWidthPx, top + barHeight)
            canvas.drawRoundRect(barRect, barCornerPx, barCornerPx, barPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (barCount * barWidthPx + (barCount - 1) * barGapPx + paddingLeft + paddingRight).toInt()
        val desiredHeight = (20 * density + paddingTop + paddingBottom).toInt()

        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }
}
