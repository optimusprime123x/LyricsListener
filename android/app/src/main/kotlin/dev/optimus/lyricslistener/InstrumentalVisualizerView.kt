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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * A mock audio-equalizer visualizer view for instrumental/blank lyric lines.
 * Five bars move through a repeating groove with stronger downbeats and softer
 * syncopated accents so the motion feels more musical than a simple sine wave.
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
    private val idleHeightFractions = floatArrayOf(0.20f, 0.30f, 0.42f, 0.30f, 0.20f)
    private val minHeightFraction = 0.12f
    private val maxHeightFraction = 1.0f

    // Each bar moves a little differently so the animation reads as a groove,
    // with the center bar carrying more of the "kick" energy.
    private val barPhaseOffsets = floatArrayOf(0f, 2.1f, 0.7f, 3.5f, 1.4f)
    private val barSpeedMultipliers = floatArrayOf(1.0f, 1.18f, 0.92f, 1.28f, 1.08f)
    private val barBeatOffsets = floatArrayOf(0f, 0.08f, 0.16f, 0.04f, 0.12f)
    private val kickWeights = floatArrayOf(0.42f, 0.68f, 1.0f, 0.72f, 0.46f)
    private val snareWeights = floatArrayOf(0.74f, 0.90f, 0.62f, 0.88f, 0.78f)
    private val shimmerWeights = floatArrayOf(0.72f, 0.58f, 0.44f, 0.58f, 0.72f)
    private val beatsPerLoop = 4f
    private val animatorLoopMs = 1480L
    private val twoPi = (Math.PI * 2.0).toFloat()

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
        animator = ValueAnimator.ofFloat(0f, beatsPerLoop).apply {
            duration = animatorLoopMs
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

    private fun wrappedBeat(value: Float): Float {
        val wrapped = value % beatsPerLoop
        return if (wrapped < 0f) wrapped + beatsPerLoop else wrapped
    }

    private fun pulseAt(beatPosition: Float, centerBeat: Float, width: Float): Float {
        val distance = abs(wrappedBeat(beatPosition - centerBeat))
        val wrappedDistance = min(distance, beatsPerLoop - distance)
        if (wrappedDistance >= width) return 0f

        val normalized = 1f - (wrappedDistance / width)
        // Smoothstep-style curve for a quick attack and softer decay.
        return normalized * normalized * (3f - 2f * normalized)
    }

    private fun animatedHeightFraction(barIndex: Int): Float {
        val beatPosition = wrappedBeat(animationProgress + barBeatOffsets[barIndex])
        val wavePhase = (animationProgress / beatsPerLoop) * twoPi

        val groove = 0.5f + 0.5f * sin(
            (wavePhase * barSpeedMultipliers[barIndex] + barPhaseOffsets[barIndex]).toDouble()
        ).toFloat()
        val ripple = 0.5f + 0.5f * sin(
            (wavePhase * (barSpeedMultipliers[barIndex] * 2.15f) - barPhaseOffsets[barIndex] * 0.85f).toDouble()
        ).toFloat()

        val kick =
            pulseAt(beatPosition, 0f, 0.34f) +
                pulseAt(beatPosition, 2f, 0.30f) * 0.82f
        val snare =
            pulseAt(beatPosition, 1f, 0.24f) * 0.72f +
                pulseAt(beatPosition, 3f, 0.22f) * 0.64f
        val shimmer =
            pulseAt(beatPosition, 0.5f, 0.17f) +
                pulseAt(beatPosition, 1.5f, 0.17f) +
                pulseAt(beatPosition, 2.5f, 0.17f) +
                pulseAt(beatPosition, 3.5f, 0.17f)

        val rhythmicEnergy =
            0.18f +
                groove * 0.26f +
                ripple * 0.12f +
                kick * kickWeights[barIndex] * 0.28f +
                snare * snareWeights[barIndex] * 0.18f +
                shimmer * shimmerWeights[barIndex] * 0.08f

        val clampedEnergy = rhythmicEnergy.coerceIn(0f, 1f)
        return minHeightFraction + (maxHeightFraction - minHeightFraction) * clampedEnergy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val totalBarsWidth = barCount * barWidthPx + (barCount - 1) * barGapPx
        val startX = (width - totalBarsWidth) / 2f
        val maxBarHeight = height.toFloat()

        for (i in 0 until barCount) {
            val heightFraction = if (isAnimating) {
                animatedHeightFraction(i)
            } else {
                idleHeightFractions[i]
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
