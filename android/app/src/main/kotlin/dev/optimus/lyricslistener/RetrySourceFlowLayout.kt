package dev.optimus.lyricslistener

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Lightweight wrapping horizontal flow layout for retry source pills inside the floating overlay,
 * avoiding a heavy Material Components dependency while supporting arbitrary screen widths and font scales.
 */
class RetrySourceFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    var horizontalSpacingPx: Int = (8f * density).toInt()
    var verticalSpacingPx: Int = (8f * density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val maxContentWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        var currentRowWidth = 0
        var currentRowHeight = 0
        var maxRowWidth = 0
        var totalHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            val neededWidth = if (currentRowWidth == 0) childWidth else currentRowWidth + horizontalSpacingPx + childWidth
            if (currentRowWidth > 0 && neededWidth > maxContentWidth) {
                maxRowWidth = max(maxRowWidth, currentRowWidth)
                totalHeight += currentRowHeight + verticalSpacingPx
                currentRowWidth = childWidth
                currentRowHeight = childHeight
            } else {
                currentRowWidth = neededWidth
                currentRowHeight = max(currentRowHeight, childHeight)
            }
        }

        if (currentRowHeight > 0) {
            maxRowWidth = max(maxRowWidth, currentRowWidth)
            totalHeight += currentRowHeight
        }

        val finalWidth = resolveSize(maxRowWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val finalHeight = resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableWidth = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL

        var cursorX = 0
        var cursorY = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (cursorX > 0 && cursorX + horizontalSpacingPx + childWidth > availableWidth) {
                cursorX = 0
                cursorY += rowHeight + verticalSpacingPx
                rowHeight = 0
            }

            val startOffset = if (cursorX == 0) 0 else cursorX + horizontalSpacingPx
            val childLeft = if (isRtl) {
                r - l - paddingRight - startOffset - childWidth
            } else {
                paddingLeft + startOffset
            }

            child.layout(childLeft, cursorY, childLeft + childWidth, cursorY + childHeight)
            cursorX = startOffset + childWidth
            rowHeight = max(rowHeight, childHeight)
        }
    }
}
