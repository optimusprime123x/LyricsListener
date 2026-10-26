package dev.optimus.lyricslistener

import kotlin.math.hypot

/** A dismissed launcher starts a new session beside the current lyrics window. */
internal class LyricsBubblePlacement {
    private var rememberedPosition: Pair<Int, Int>? = null

    fun remember(x: Int, y: Int) {
        rememberedPosition = Pair(x, y)
    }

    fun forget() {
        rememberedPosition = null
    }

    fun positionFor(bounds: LyricsWindowBounds, size: Int, centerY: Int): Pair<Int, Int> {
        val position = rememberedPosition ?: Pair((bounds.width - size) / 2, centerY)
        return bounds.constrainPosition(position.first, position.second, size, size)
    }
}

/** Attraction follows the unmodified drag position, so pulling away always releases the bubble. */
internal object LyricsBubbleMagnet {
    data class Pull(val x: Float = 0f, val y: Float = 0f, val captured: Boolean = false)

    fun pull(
        bubbleX: Float, bubbleY: Float, targetX: Float, targetY: Float,
        captureRadius: Float, attractionRadius: Float
    ): Pull {
        val dx = targetX - bubbleX
        val dy = targetY - bubbleY
        val distance = hypot(dx, dy)
        if (distance >= attractionRadius) return Pull()
        val captured = distance <= captureRadius
        val proximity = if (captured) 1f else
            ((attractionRadius - distance) / (attractionRadius - captureRadius)).coerceIn(0f, 1f)
        val strength = proximity * proximity * (3f - 2f * proximity)
        return Pull(dx * strength, dy * strength, captured)
    }
}
