package dev.optimus.lyricslistener

/** Dimensions of the screen area available to the overlay, excluding system bars and cutouts. */
internal data class LyricsWindowBounds(val width: Int, val height: Int) {
    /** Window offsets use CENTER gravity, including positions saved by earlier app versions. */
    fun constrainPosition(x: Int, y: Int, windowWidth: Int, windowHeight: Int): Pair<Int, Int> =
        Pair(constrainOffset(x, windowWidth, width), constrainOffset(y, windowHeight, height))

    fun constrainLyricsHeight(requestedHeight: Int, headerHeight: Int): Int =
        requestedHeight.coerceAtMost((height - headerHeight).coerceAtLeast(1))

    private fun constrainOffset(offset: Int, windowSize: Int, availableSize: Int): Int {
        val remainingSpace = (availableSize - windowSize).coerceAtLeast(0)
        // CENTER gravity rounds the starting edge down when the remaining space is odd.
        return offset.coerceIn(-remainingSpace / 2, remainingSpace - remainingSpace / 2)
    }
}
