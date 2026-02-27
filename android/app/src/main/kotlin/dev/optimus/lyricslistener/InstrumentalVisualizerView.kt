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
 * A mock 3-bar audio visualizer view for instrumental/blank lyric lines.
 * Each bar oscillates at a different phase to create a wave-like animation.
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

    private val barCount = 3
    private val barWidthDp = 4f
    private val barGapDp = 3.5f
    private val barCornerDp = 2f
    private val minHeightFraction = 0.2f
    private val maxHeightFraction = 1.0f

    // Phase offsets for each bar to create wave effect
    private val barPhaseOffsets = floatArrayOf(0f, 1.2f, 0.6f)

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
        animator = ValueAnimator.ofFloat(0f, (2 * kotlin.math.PI).toFloat()).apply {
            duration = 1200
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
            val phase = animationProgress + barPhaseOffsets[i]
            val heightFraction = minHeightFraction +
                    (maxHeightFraction - minHeightFraction) * ((sin(phase.toDouble()) + 1f) / 2f).toFloat()

            val barHeight = maxBarHeight * heightFraction
            val left = startX + i * (barWidthPx + barGapPx)
            val top = (maxBarHeight - barHeight) / 2f

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
