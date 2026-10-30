package dev.optimus.lyricslistener

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter(
    private val context: Context,
    private var lyricLines: List<TimedLyricLine>
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var highlightedPosition = -1
    private var isSyncedMode = false
    private var isPlaying = false
    private var isMiniMode = false
    private var isExpanded = false

    private var normalLineTextColor: Int = Color.WHITE
    private var highlightedLineTextColor: Int = Color.WHITE
    private var highlightedLineBackgroundColor: Int = Color.argb(70, 200, 200, 200)
    private var inactiveSyncedLineTextColor: Int = Color.parseColor("#8CFFFFFF")
    private var showHighlightedLineBackground: Boolean = true
    private val attributionTextColor: Int = Color.parseColor("#A0A0A0")

    // Base text size for lyrics
    private val normalTextSizeSp = 16f
    private val highlightedTextSizeSp = 18f
    private val attributionTextSizeSp = 11f

    private val fadeInterpolator = DecelerateInterpolator(2f)

    fun updateThemeColors(
        normalLineTextColor: Int,
        highlightedLineTextColor: Int,
        highlightedLineBackgroundColor: Int,
        showHighlightedLineBackground: Boolean
    ) {
        this.normalLineTextColor = normalLineTextColor
        this.highlightedLineTextColor = highlightedLineTextColor
        this.highlightedLineBackgroundColor = highlightedLineBackgroundColor
        this.showHighlightedLineBackground = showHighlightedLineBackground
        inactiveSyncedLineTextColor = ColorUtils.setAlphaComponent(highlightedLineTextColor, 0x8C)
        notifyDataSetChanged()
    }


    private val highlightRadius = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 20f, context.resources.displayMetrics
    )

    // Pattern for detecting instrumental/blank lyric lines
    private val instrumentalPattern = "🎶 ... 🎶"

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = bind(holder, position, false)

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        bind(holder, position, payloads.contains(HIGHLIGHT_PAYLOAD))
    }

    private fun bind(holder: ViewHolder, position: Int, highlightChanged: Boolean) {
        val line = lyricLines[position]
        holder.transition?.cancel()
        holder.transition = null
        val sameLine = holder.boundPosition == position
        val wasVisible = holder.itemView.visibility == View.VISIBLE
        holder.boundPosition = position
        val content = if (line.text == instrumentalPattern) holder.visualizer else holder.lyricText
        val previousAlpha = content.alpha
        val previousScaleX = content.scaleX
        val previousScaleY = content.scaleY
        val previousBackground = holder.highlightAmount
        val previousTextColor = holder.lyricText.currentTextColor

        // Mini mode: only show the highlighted line
        if (isMiniMode && isSyncedMode && position != highlightedPosition) {
            holder.itemView.visibility = View.GONE
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                height = 0
            }
            holder.visualizer.stopAnimation()
            return
        } else {
            holder.itemView.visibility = View.VISIBLE
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        // Check if this is an instrumental/blank line
        val isInstrumental = line.text == instrumentalPattern

        if (isInstrumental) {
            holder.lyricText.visibility = View.GONE
            holder.visualizer.visibility = View.VISIBLE
            holder.visualizer.setBarColor(
                if (isSyncedMode && position == highlightedPosition) {
                    highlightedLineTextColor
                } else if (isSyncedMode) {
                    inactiveSyncedLineTextColor
                } else {
                    normalLineTextColor
                }
            )
            holder.visualizer.alpha = if (isSyncedMode && highlightedPosition != -1 && position != highlightedPosition) {
                if (isExpanded) {
                    if (position < highlightedPosition) 0.30f else 0.45f
                } else {
                    if (position < highlightedPosition) 0.20f else 0.35f
                }
            } else {
                0.7f
            }
            // Only animate when this is the highlighted line AND music is playing
            if (isSyncedMode && position == highlightedPosition && isPlaying) {
                holder.visualizer.startAnimation()
            } else {
                holder.visualizer.stopAnimation()
            }

            // Apply highlight background for instrumental lines too
            if (isSyncedMode && position == highlightedPosition && showHighlightedLineBackground) {
                val bg = GradientDrawable().apply {
                    setColor(highlightedLineBackgroundColor)
                    cornerRadius = highlightRadius
                }
                holder.itemView.background = bg
            } else {
                holder.itemView.background = null
            }
            finishBinding(holder, content, highlightChanged && sameLine, wasVisible,
                previousAlpha, previousScaleX, previousScaleY, previousBackground, previousTextColor)
            return
        }

        // Normal lyric line — hide visualizer, show text
        holder.lyricText.visibility = View.VISIBLE
        holder.visualizer.visibility = View.GONE
        holder.visualizer.stopAnimation()
        holder.lyricText.text = line.text
        holder.lyricText.gravity = Gravity.START
        holder.lyricText.textAlignment = View.TEXT_ALIGNMENT_VIEW_START

        if (line.timestamp == LyricService.ATTRIBUTION_TIMESTAMP) {
            // This is the attribution line, style it specially and stop.
            holder.itemView.background = null
            holder.highlightAmount = 0f
            holder.lyricText.setTextColor(attributionTextColor)
            holder.lyricText.setTextSize(TypedValue.COMPLEX_UNIT_SP, attributionTextSizeSp)
            holder.lyricText.setTypeface(null, Typeface.ITALIC)
            holder.lyricText.alpha = 0.7f
            holder.lyricText.letterSpacing = 0.02f
            holder.lyricText.scaleX = 1.0f
            holder.lyricText.scaleY = 1.0f
            return // IMPORTANT: Skip the rest of the highlighting logic
        }

        // Reset default appearance first
        holder.itemView.background = null
        holder.lyricText.setTextColor(if (isSyncedMode) inactiveSyncedLineTextColor else normalLineTextColor)
        holder.lyricText.setTypeface(null, Typeface.NORMAL)
        holder.lyricText.setTextSize(TypedValue.COMPLEX_UNIT_SP, normalTextSizeSp)
        holder.lyricText.alpha = 1.0f
        holder.lyricText.letterSpacing = 0.01f

        if (isSyncedMode) {
            if (position == highlightedPosition) {
                // Keep the existing highlighted text emphasis and optional rounded background.
                if (showHighlightedLineBackground) {
                    val bg = GradientDrawable().apply {
                        setColor(highlightedLineBackgroundColor)
                        cornerRadius = highlightRadius
                    }
                    holder.itemView.background = bg
                }
                holder.lyricText.setTextColor(highlightedLineTextColor)
                holder.lyricText.setTypeface(null, Typeface.BOLD)
                holder.lyricText.setTextSize(TypedValue.COMPLEX_UNIT_SP, highlightedTextSizeSp)
                holder.lyricText.alpha = 1.0f
                holder.lyricText.letterSpacing = 0.015f

                holder.lyricText.scaleX = 1.05f
                holder.lyricText.scaleY = 1.02f
            } else {
                holder.lyricText.scaleX = 1f
                holder.lyricText.scaleY = 1f

                // Other lines in synced mode (past or upcoming)
                holder.lyricText.setTextColor(normalLineTextColor)

                if (highlightedPosition != -1) { // If there is an active highlight
                    val targetAlpha: Float
                    if (position < highlightedPosition) {
                        // Past lines fade out more aggressively
                        val diff = highlightedPosition - position
                        targetAlpha = if (isExpanded) {
                            when {
                                diff == 1 -> 0.7f
                                diff == 2 -> 0.5f
                                else -> 0.35f
                            }
                        } else {
                            when {
                                diff == 1 -> 0.6f
                                diff == 2 -> 0.4f
                                else -> 0.25f
                            }
                        }
                    } else {
                        // Upcoming lines keep some readability
                        val diff = position - highlightedPosition
                        targetAlpha = if (isExpanded) {
                            when (diff) {
                                1 -> 0.85f
                                2 -> 0.65f
                                3 -> 0.5f
                                else -> 0.35f
                            }
                        } else {
                            when (diff) {
                                1 -> 0.75f
                                2 -> 0.5f
                                else -> 0.25f
                            }
                        }
                    }
                    holder.lyricText.alpha = targetAlpha
                } else {
                    // No active line: restore the full-opacity resting state.
                    holder.lyricText.alpha = 1.0f
                }
            }
        } else {
            // Non-synced mode (plain lyrics) - full opacity, normal style
            holder.lyricText.alpha = 1.0f
            holder.lyricText.scaleX = 1.0f
            holder.lyricText.scaleY = 1.0f
        }
        finishBinding(holder, content, highlightChanged && sameLine, wasVisible,
            previousAlpha, previousScaleX, previousScaleY, previousBackground, previousTextColor)
    }

    /** Continue from the displayed opacity instead of resetting to full brightness on every bind. */
    private fun finishBinding(
        holder: ViewHolder,
        content: View,
        highlightChanged: Boolean,
        wasVisible: Boolean,
        previousAlpha: Float,
        previousScaleX: Float,
        previousScaleY: Float,
        previousBackground: Float,
        previousTextColor: Int
    ) {
        val targetAlpha = content.alpha
        val targetScaleX = content.scaleX
        val targetScaleY = content.scaleY
        val targetTextColor = holder.lyricText.currentTextColor
        val targetBackground = if (holder.itemView.background != null) 1f else 0f
        val background = (holder.itemView.background as? GradientDrawable) ?: GradientDrawable().apply {
            setColor(highlightedLineBackgroundColor)
            cornerRadius = highlightRadius
        }
        holder.itemView.background = background
        fun apply(alpha: Float, scaleX: Float, scaleY: Float, highlight: Float, colorProgress: Float = 1f) {
            content.alpha = alpha
            content.scaleX = scaleX
            content.scaleY = scaleY
            holder.highlightAmount = highlight
            background.alpha = (highlight.coerceIn(0f, 1f) * 255).toInt()
            if (content is TextView) {
                content.setTextColor(ColorUtils.blendARGB(previousTextColor, targetTextColor, colorProgress))
            }
        }
        val animate = highlightChanged && holder.itemView.isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()
        if (!animate) {
            apply(targetAlpha, targetScaleX, targetScaleY, targetBackground)
            return
        }
        // Mini changes the visible row immediately; give its new line a short, gentle entrance.
        val miniEntrance = isMiniMode && !wasVisible
        val startAlpha = if (miniEntrance) targetAlpha * 0.65f else previousAlpha
        val startScaleX = if (miniEntrance) targetScaleX else previousScaleX
        val startScaleY = if (miniEntrance) targetScaleY else previousScaleY
        val startBackground = if (miniEntrance) 0f else previousBackground
        apply(startAlpha, startScaleX, startScaleY, startBackground, 0f)
        holder.transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (miniEntrance) 180L else 300L
            interpolator = fadeInterpolator
            addUpdateListener {
                val progress = it.animatedValue as Float
                apply(
                    startAlpha + (targetAlpha - startAlpha) * progress,
                    startScaleX + (targetScaleX - startScaleX) * progress,
                    startScaleY + (targetScaleY - startScaleY) * progress,
                    startBackground + (targetBackground - startBackground) * progress,
                    progress
                )
            }
            start()
        }
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position in lyricLines.indices) bind(holder, position, false)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.transition?.cancel()
        holder.transition = null
        holder.visualizer.stopAnimation()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.transition?.cancel()
        holder.transition = null
        holder.visualizer.stopAnimation()
        holder.boundPosition = RecyclerView.NO_POSITION
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = lyricLines.size

    fun updateLyrics(newLines: List<TimedLyricLine>, synced: Boolean) {
        this.lyricLines = newLines
        this.isSyncedMode = synced
        this.highlightedPosition = -1
        notifyDataSetChanged()
    }

    fun setHighlight(position: Int) {
        if (!isSyncedMode) return
        val next = position.takeIf {
            it in lyricLines.indices && lyricLines[it].timestamp != LyricService.ATTRIBUTION_TIMESTAMP
        } ?: -1
        if (next == highlightedPosition) return
        val previous = highlightedPosition
        highlightedPosition = next
        val changed = linkedSetOf<Int>()
        if (previous == -1 || next == -1 || kotlin.math.abs(next - previous) > 1) {
            // Seeking or clearing focus can change the past/future state of every visible line.
            changed.addAll(lyricLines.indices)
        } else {
            for (center in listOf(previous, next)) {
                for (index in center - 4..center + 4) {
                    if (index in lyricLines.indices) changed.add(index)
                }
            }
        }
        // One payload per row prevents overlapping fades from being restarted by duplicate binds.
        changed.forEach { notifyItemChanged(it, HIGHLIGHT_PAYLOAD) }
    }

    fun getCurrentHighlightedPosition(): Int = highlightedPosition

    fun setWindowMode(mini: Boolean, expanded: Boolean) {
        if (isMiniMode == mini && isExpanded == expanded) return
        isMiniMode = mini
        isExpanded = expanded
        notifyDataSetChanged()
    }

    fun isSynced(): Boolean = isSyncedMode

    fun setPlayingState(playing: Boolean) {
        val changed = isPlaying != playing
        isPlaying = playing
        // Always refresh the highlighted instrumental line — even if isPlaying hasn't
        // changed — to recover from any rebind that may have seen stale state.
        if (highlightedPosition in 0 until lyricLines.size &&
            lyricLines[highlightedPosition].text == instrumentalPattern) {
            notifyItemChanged(highlightedPosition)
        } else if (changed) {
            // No instrumental highlighted, but state changed — future binds will pick it up
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lyricText: TextView = itemView.findViewById(R.id.lyricLineText)
        val visualizer: InstrumentalVisualizerView = itemView.findViewById(R.id.instrumentalVisualizer)
        var boundPosition = RecyclerView.NO_POSITION
        var highlightAmount = 0f
        var transition: ValueAnimator? = null
    }

    private companion object {
        const val HIGHLIGHT_PAYLOAD = "highlight"
    }
}
