package dev.optimus.lyricslistener

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import kotlin.math.roundToInt

/**
 * Computes adaptive brightness, saturation, and regional top/bottom scrim opacity for
 * the blurred album-art colour field behind white overlay text.
 */
internal object OverlayArtworkDimming {
    // Trim the outer ~4% border on each side so thin white cover borders or edge scan
    // artifacts are excluded from both the rendered colour field and its sampling.
    internal const val EDGE_TRIM_FRACTION = 0.04f
    // ~26% extra black while a focused panel is open, capped at ~60% total.
    private const val FOCUS_SCRIM_EXTRA_ALPHA = 66
    private const val FOCUS_SCRIM_MAX_ALPHA = 152

    data class LuminanceProfile(
        val meanLuminance: Double,
        val p85Luminance: Double,
        val p95Luminance: Double = p85Luminance,
        val topLuminance: Double = meanLuminance,
        val bottomLuminance: Double = meanLuminance
    ) {
        val effectiveLuminance: Double
            get() = maxOf(
                meanLuminance,
                p85Luminance * 0.80,
                p95Luminance * 0.68
            ).coerceIn(0.0, 1.0)
    }

    data class Spec(
        val brightnessScale: Float,
        val saturation: Float,
        val topScrimAlpha: Int,
        val bottomScrimAlpha: Int
    ) {
        val maxScrimAlpha: Int
            get() = maxOf(topScrimAlpha, bottomScrimAlpha)

        val meanScrimAlpha: Float
            get() = (topScrimAlpha + bottomScrimAlpha) / 2f

        /** Effective 0..1 sRGB channel multiplier combining brightness scale and average scrim. */
        val combinedSrgbMultiplier: Float
            get() = brightnessScale * (1f - (meanScrimAlpha.coerceIn(0f, 255f) / 255f))

        fun toColorFilter(): ColorMatrixColorFilter {
            val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
            val brightnessMatrix = ColorMatrix(
                floatArrayOf(
                    brightnessScale, 0f, 0f, 0f, 0f,
                    0f, brightnessScale, 0f, 0f, 0f,
                    0f, 0f, brightnessScale, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            saturationMatrix.postConcat(brightnessMatrix)
            return ColorMatrixColorFilter(saturationMatrix)
        }

        /**
         * Deeper scrim used while a focused panel (e.g. retry) is open, so box-free text
         * stays legible over any artwork without adding containers.
         */
        fun withFocusBoost(extraAlpha: Int = FOCUS_SCRIM_EXTRA_ALPHA, maxAlpha: Int = FOCUS_SCRIM_MAX_ALPHA): Spec {
            fun boost(alpha: Int) = (alpha + extraAlpha).coerceAtMost(maxOf(maxAlpha, alpha)).coerceIn(0, 255)
            return copy(topScrimAlpha = boost(topScrimAlpha), bottomScrimAlpha = boost(bottomScrimAlpha))
        }

        fun toScrimDrawable(): GradientDrawable {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(topScrimAlpha.coerceIn(0, 255), 0, 0, 0),
                    Color.argb(bottomScrimAlpha.coerceIn(0, 255), 0, 0, 0)
                )
            )
        }
    }

    fun analyzeLuminanceGrid(grid: Array<DoubleArray>): LuminanceProfile {
        val rowCount = grid.size
        val colCount = grid.firstOrNull()?.size ?: 0
        if (rowCount == 0 || colCount == 0) {
            return LuminanceProfile(meanLuminance = 0.25, p85Luminance = 0.25)
        }

        val allSamples = DoubleArray(rowCount * colCount)
        val bandRows = (rowCount / 3).coerceAtLeast(1)
        val topSamples = DoubleArray(bandRows * colCount)
        val bottomSamples = DoubleArray(bandRows * colCount)

        var allIdx = 0
        var topIdx = 0
        var bottomIdx = 0
        val bottomStartRow = rowCount - bandRows

        for (r in 0 until rowCount) {
            val row = grid[r]
            for (c in 0 until colCount) {
                val lum = row[c].coerceIn(0.0, 1.0)
                allSamples[allIdx++] = lum
                if (r < bandRows) {
                    topSamples[topIdx++] = lum
                }
                if (r >= bottomStartRow) {
                    bottomSamples[bottomIdx++] = lum
                }
            }
        }

        val base = analyzeLuminanceSamples(allSamples)
        val topBand = analyzeLuminanceSamples(topSamples)
        val bottomBand = analyzeLuminanceSamples(bottomSamples)

        return base.copy(
            topLuminance = maxOf(topBand.meanLuminance, topBand.p85Luminance * 0.82),
            bottomLuminance = maxOf(bottomBand.meanLuminance, bottomBand.p85Luminance * 0.82)
        )
    }

    fun analyzeLuminanceSamples(samples: DoubleArray): LuminanceProfile {
        if (samples.isEmpty()) {
            return LuminanceProfile(meanLuminance = 0.25, p85Luminance = 0.25)
        }
        val clamped = DoubleArray(samples.size) { samples[it].coerceIn(0.0, 1.0) }
        val mean = clamped.average()
        clamped.sort()
        val p85Index = ((clamped.size - 1) * 0.85).roundToInt().coerceIn(0, clamped.lastIndex)
        val p95Index = ((clamped.size - 1) * 0.95).roundToInt().coerceIn(0, clamped.lastIndex)
        val p85 = clamped[p85Index]
        val p95 = clamped[p95Index]
        return LuminanceProfile(
            meanLuminance = mean,
            p85Luminance = p85,
            p95Luminance = p95,
            topLuminance = mean,
            bottomLuminance = mean
        )
    }

    fun computeSpec(profile: LuminanceProfile, blurActive: Boolean): Spec {
        val eff = profile.effectiveLuminance
        if (!blurActive) {
            val brightness = when {
                eff <= 0.15 -> 0.85f
                eff <= 0.50 -> lerp(0.85f, 0.68f, (eff - 0.15) / 0.35)
                else -> lerp(0.68f, 0.56f, (eff - 0.50) / 0.50)
            }
            val scrim = when {
                eff <= 0.15 -> 50
                else -> lerp(50f, 100f, (eff - 0.15) / 0.85).roundToInt()
            }.coerceIn(0, 255)
            return Spec(
                brightnessScale = brightness,
                saturation = 1.15f,
                topScrimAlpha = scrim,
                bottomScrimAlpha = scrim
            )
        }

        val brightness = when {
            eff <= 0.12 -> 0.92f
            eff <= 0.45 -> lerp(0.92f, 0.74f, (eff - 0.12) / 0.33)
            else -> lerp(0.74f, 0.56f, (eff - 0.45) / 0.55)
        }

        val baseScrim = blurScrimAlphaForLuminance(eff)
        val topTarget = maxOf(eff * 0.85, profile.topLuminance.coerceIn(0.0, 1.0))
        val bottomTarget = maxOf(eff * 0.85, profile.bottomLuminance.coerceIn(0.0, 1.0))
        val minBandScrim = (baseScrim * 0.85f).roundToInt()

        val topScrim = maxOf(minBandScrim, blurScrimAlphaForLuminance(topTarget)).coerceIn(0, 255)
        val bottomScrim = maxOf(minBandScrim, blurScrimAlphaForLuminance(bottomTarget)).coerceIn(0, 255)

        val saturation = when {
            eff <= 0.60 -> 1.18f
            else -> lerp(1.18f, 1.10f, (eff - 0.60) / 0.40)
        }

        return Spec(
            brightnessScale = brightness,
            saturation = saturation,
            topScrimAlpha = topScrim,
            bottomScrimAlpha = bottomScrim
        )
    }

    /**
     * Smooth scrim alpha curve for blurred artwork:
     * - Very dark (<= 0.12): 0
     * - Low-to-mid (0.12..0.25): 0 -> 18 (~7% subtle veil)
     * - Mid-tone (0.25..0.45): 18 -> 34 (~7%..13% gentle glassy scrim)
     * - Bright/white (0.45..1.00): 34 -> 84 (~13%..33%)
     */
    private fun blurScrimAlphaForLuminance(lum: Double): Int {
        val clamped = lum.coerceIn(0.0, 1.0)
        return when {
            clamped <= 0.12 -> 0
            clamped <= 0.25 -> lerp(0f, 18f, (clamped - 0.12) / 0.13).roundToInt()
            clamped <= 0.45 -> lerp(18f, 34f, (clamped - 0.25) / 0.20).roundToInt()
            else -> lerp(34f, 84f, (clamped - 0.45) / 0.55).roundToInt()
        }.coerceIn(0, 255)
    }

    /**
     * Vertical blur radius adapted to window height so squeezing the full cover into a
     * short Mini pill preserves its top-to-bottom colour gradient instead of muddling it.
     */
    fun computeVerticalBlurRadiusPx(viewHeightPx: Int, maxBlurRadiusPx: Float = 40f): Float {
        if (viewHeightPx <= 0) return maxBlurRadiusPx
        return (viewHeightPx * 0.14f).coerceIn(14f, maxBlurRadiusPx)
    }

    private fun lerp(start: Float, end: Float, fraction: Double): Float {
        val t = fraction.coerceIn(0.0, 1.0).toFloat()
        return start + (end - start) * t
    }
}
