package dev.optimus.lyricslistener

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class OverlayArtworkDimmingTest {

    @Test
    fun `dark album covers preserve almost full brightness without scrim`() {
        val samples = DoubleArray(400) { 0.08 }
        val profile = OverlayArtworkDimming.analyzeLuminanceSamples(samples)
        val spec = OverlayArtworkDimming.computeSpec(profile, blurActive = true)

        assertEquals(0.92f, spec.brightnessScale, 0.001f)
        assertEquals(0, spec.topScrimAlpha)
        assertEquals(0, spec.bottomScrimAlpha)
        assertEquals(0, spec.maxScrimAlpha)
        assertTrue(spec.combinedSrgbMultiplier > 0.90f)
    }

    @Test
    fun `mid-tone album art applies a subtle glassy scrim while staying vibrant and continuous`() {
        val slightlyDarker = OverlayArtworkDimming.computeSpec(
            OverlayArtworkDimming.LuminanceProfile(meanLuminance = 0.29, p85Luminance = 0.31),
            blurActive = true
        )
        val normalMidTone = OverlayArtworkDimming.computeSpec(
            OverlayArtworkDimming.LuminanceProfile(meanLuminance = 0.32, p85Luminance = 0.36),
            blurActive = true
        )

        // Mid-tone covers receive a gentle ~7%..13% scrim (alpha 18..34) without harsh cliffs.
        assertTrue(
            "Expected subtle mid-tone scrim in 18..34, got ${normalMidTone.meanScrimAlpha}",
            normalMidTone.meanScrimAlpha in 18f..34f
        )
        assertTrue(normalMidTone.combinedSrgbMultiplier > 0.68f)
        assertTrue(abs(slightlyDarker.combinedSrgbMultiplier - normalMidTone.combinedSrgbMultiplier) < 0.05f)
    }

    @Test
    fun `regional grid analysis tames bright top sky and whitish bottom bands locally`() {
        // Cover with bright sky at top, mid-tone centre, and dark foreground at bottom
        val skyTopGrid = Array(21) { row ->
            val lum = when {
                row < 7 -> 0.78
                row < 14 -> 0.28
                else -> 0.14
            }
            DoubleArray(21) { lum }
        }
        val skyProfile = OverlayArtworkDimming.analyzeLuminanceGrid(skyTopGrid)
        val skySpec = OverlayArtworkDimming.computeSpec(skyProfile, blurActive = true)
        assertTrue(
            "Expected top scrim (${skySpec.topScrimAlpha}) > bottom scrim (${skySpec.bottomScrimAlpha})",
            skySpec.topScrimAlpha > skySpec.bottomScrimAlpha
        )

        // Cover with dark upper artwork and a thin (~10%) bright whitish band near the bottom
        val bottomWhiteBandGrid = Array(20) { row ->
            val lum = if (row >= 18) 0.90 else 0.14
            DoubleArray(20) { lum }
        }
        val bottomProfile = OverlayArtworkDimming.analyzeLuminanceGrid(bottomWhiteBandGrid)
        // Even though <15% of rows are white (so p85 stays dark), p95 and bottomLuminance catch it.
        assertEquals(0.14, bottomProfile.p85Luminance, 0.001)
        assertEquals(0.90, bottomProfile.p95Luminance, 0.001)
        assertTrue(bottomProfile.effectiveLuminance > 0.55)

        val bottomSpec = OverlayArtworkDimming.computeSpec(bottomProfile, blurActive = true)
        assertTrue(
            "Expected bottom scrim (${bottomSpec.bottomScrimAlpha}) > top scrim (${bottomSpec.topScrimAlpha})",
            bottomSpec.bottomScrimAlpha > bottomSpec.topScrimAlpha
        )
    }

    @Test
    fun `bright hotspot on an otherwise dark cover increases effective luminance check`() {
        // 75% dark (0.06) + 25% bright portrait/sky (0.82) -> p85 catches the bright region
        val samples = DoubleArray(400) { idx -> if (idx < 300) 0.06 else 0.82 }
        val profile = OverlayArtworkDimming.analyzeLuminanceSamples(samples)
        assertTrue(profile.meanLuminance < 0.30)
        assertEquals(0.82, profile.p85Luminance, 0.001)
        assertTrue(profile.effectiveLuminance > 0.60)

        val spec = OverlayArtworkDimming.computeSpec(profile, blurActive = true)
        assertTrue(spec.maxScrimAlpha > 34)
        assertTrue(spec.combinedSrgbMultiplier < 0.60f)
    }

    @Test
    fun `combined dimming decreases monotonically and keeps white text legible up to pure white`() {
        var previousMultiplier = 1.0f
        for (step in 1..20) {
            val lum = step * 0.05
            val profile = OverlayArtworkDimming.LuminanceProfile(meanLuminance = lum, p85Luminance = lum)
            val spec = OverlayArtworkDimming.computeSpec(profile, blurActive = true)
            assertTrue(
                "Expected monotonic dimming at luminance $lum",
                spec.combinedSrgbMultiplier <= previousMultiplier + 1e-4f
            )
            previousMultiplier = spec.combinedSrgbMultiplier

            val approxResultingLuminance = lum * spec.combinedSrgbMultiplier.toDouble().pow(2.2)
            val wcagContrastWithWhite = 1.05 / (approxResultingLuminance + 0.05)
            assertTrue(
                "Expected readable white text contrast at luminance $lum, got $wcagContrastWithWhite",
                wcagContrastWithWhite >= 4.2
            )
        }
    }

    @Test
    fun `vertical blur radius adapts to short Mini heights while keeping full blur on taller windows`() {
        val miniBlur = OverlayArtworkDimming.computeVerticalBlurRadiusPx(viewHeightPx = 120, maxBlurRadiusPx = 40f)
        val compactBlur = OverlayArtworkDimming.computeVerticalBlurRadiusPx(viewHeightPx = 360, maxBlurRadiusPx = 40f)

        assertEquals(16.8f, miniBlur, 0.05f)
        assertEquals(40f, compactBlur, 0.05f)
    }

    @Test
    fun `focus boost deepens scrim for retry panel readability without exceeding cap`() {
        val dark = OverlayArtworkDimming.computeSpec(
            OverlayArtworkDimming.LuminanceProfile(meanLuminance = 0.08, p85Luminance = 0.08),
            blurActive = true
        ).withFocusBoost()
        assertTrue(dark.topScrimAlpha in 60..72)
        assertEquals(dark.topScrimAlpha, dark.bottomScrimAlpha)

        val bright = OverlayArtworkDimming.computeSpec(
            OverlayArtworkDimming.LuminanceProfile(meanLuminance = 0.95, p85Luminance = 0.98),
            blurActive = true
        )
        val boostedBright = bright.withFocusBoost()
        assertTrue(boostedBright.maxScrimAlpha >= bright.maxScrimAlpha)
        assertTrue(boostedBright.maxScrimAlpha <= maxOf(152, bright.maxScrimAlpha))
    }

    @Test
    fun `legacy non-blur mode applies steadier scrim for sharp artwork edges`() {
        val profile = OverlayArtworkDimming.LuminanceProfile(meanLuminance = 0.30, p85Luminance = 0.34)
        val blurred = OverlayArtworkDimming.computeSpec(profile, blurActive = true)
        val unblurred = OverlayArtworkDimming.computeSpec(profile, blurActive = false)

        assertTrue(unblurred.meanScrimAlpha > blurred.meanScrimAlpha)
    }
}
