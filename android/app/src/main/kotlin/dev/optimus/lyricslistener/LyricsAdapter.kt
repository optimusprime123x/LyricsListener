package dev.optimus.lyricslistener

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter(
    private val context: Context,
    private var lyricLines: List<TimedLyricLine>
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var highlightedPosition = -1
    private var isSyncedMode = false
    private var isPlaying = false

    private var normalLineTextColor: Int = Color.WHITE
    private var highlightedLineTextColor: Int = Color.WHITE
    private var highlightedLineBackgroundColor: Int = Color.argb(70, 200, 200, 200)
    private val attributionTextColor: Int = Color.parseColor("#A0A0A0")

    // Base text size for lyrics
    private val normalTextSizeSp = 16f
    private val highlightedTextSizeSp = 18f
    private val attributionTextSizeSp = 11f

    // Spring-like interpolator for bouncy highlight animations
    private val springInterpolator = OvershootInterpolator(2.5f)

    fun updateThemeColors(
        normalLineTextColor: Int,
        highlightedLineTextColor: Int,
        highlightedLineBackgroundColor: Int
    ) {
        this.normalLineTextColor = normalLineTextColor
        this.highlightedLineTextColor = highlightedLineTextColor
        this.highlightedLineBackgroundColor = highlightedLineBackgroundColor
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

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lyricLines[position]

        // Cancel any ongoing animations on rebind
        holder.lyricText.animate().cancel()
        holder.visualizer.stopAnimation()

        // Check if this is an instrumental/blank line
        val isInstrumental = line.text == instrumentalPattern

        if (isInstrumental) {
            holder.lyricText.visibility = View.GONE
            holder.visualizer.visibility = View.VISIBLE
            holder.visualizer.setBarColor(
                if (isSyncedMode && position == highlightedPosition) highlightedLineTextColor else normalLineTextColor
            )
            holder.visualizer.alpha = if (isSyncedMode && highlightedPosition != -1 && position != highlightedPosition) {
                if (position < highlightedPosition) 0.40f else 0.60f
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
            if (isSyncedMode && position == highlightedPosition) {
                val bg = GradientDrawable().apply {
                    setColor(highlightedLineBackgroundColor)
                    cornerRadius = highlightRadius
                }
                holder.itemView.background = bg
            } else {
                holder.itemView.background = null
            }
            return
        }

        // Normal lyric line — hide visualizer, show text
        holder.lyricText.visibility = View.VISIBLE
        holder.visualizer.visibility = View.GONE
        holder.visualizer.stopAnimation()
        holder.lyricText.text = line.text

        if (line.timestamp == LyricService.ATTRIBUTION_TIMESTAMP) {
            // This is the attribution line, style it specially and stop.
            holder.itemView.background = null
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
        holder.lyricText.setTextColor(normalLineTextColor)
        holder.lyricText.setTypeface(null, Typeface.NORMAL)
        holder.lyricText.setTextSize(TypedValue.COMPLEX_UNIT_SP, normalTextSizeSp)
        holder.lyricText.alpha = 1.0f
        holder.lyricText.letterSpacing = 0.01f

        if (isSyncedMode) {
            if (position == highlightedPosition) {
                // Current highlighted line — M3E rounded pill background with spring-physics scale emphasis
                val bg = GradientDrawable().apply {
                    setColor(highlightedLineBackgroundColor)
                    cornerRadius = highlightRadius
                }
                holder.itemView.background = bg
                holder.lyricText.setTextColor(highlightedLineTextColor)
                holder.lyricText.setTypeface(null, Typeface.BOLD)
                holder.lyricText.setTextSize(TypedValue.COMPLEX_UNIT_SP, highlightedTextSizeSp)
                holder.lyricText.alpha = 1.0f
                holder.lyricText.letterSpacing = 0.015f

                // Wavy stretch effect: scaleX overshoots more than scaleY for a fluid "wave" feel
                // OvershootInterpolator gives a spring-like bounce past the target, then settles
                if (kotlin.math.abs(holder.lyricText.scaleX - 1.05f) > 0.005f) {
                    holder.lyricText.animate()
                        .scaleX(1.05f)
                        .scaleY(1.02f)
                        .setDuration(400)
                        .setInterpolator(springInterpolator)
                        .start()
                }
            } else {
                // Animate scale back to normal with gentle deceleration
                if (kotlin.math.abs(holder.lyricText.scaleX - 1.0f) > 0.005f) {
                    holder.lyricText.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(250)
                        .setInterpolator(DecelerateInterpolator(2.0f))
                        .start()
                }

                // Other lines in synced mode (past or upcoming)
                holder.lyricText.setTextColor(normalLineTextColor)

                if (highlightedPosition != -1) { // If there is an active highlight
                    val targetAlpha: Float
                    if (position < highlightedPosition) {
                        // Past lines — fade out progressively
                        val diff = highlightedPosition - position
                        targetAlpha = when {
                            diff == 1 -> 0.50f
                            diff == 2 -> 0.38f
                            else -> 0.28f
                        }
                    } else {
                        // Upcoming lines — slightly brighter than past
                        val diff = position - highlightedPosition
                        targetAlpha = when (diff) {
                            1 -> 0.75f
                            2 -> 0.55f
                            3 -> 0.42f
                            else -> 0.32f
                        }
                    }
                    // Smoothly animate alpha transitions for fluid fading
                    holder.lyricText.animate()
                        .alpha(targetAlpha)
                        .setDuration(300)
                        .setInterpolator(DecelerateInterpolator(2.0f))
                        .start()
                } else {
                    // Synced mode, but no line is currently highlighted
                    holder.lyricText.alpha = 0.65f
                }
            }
        } else {
            // Non-synced mode (plain lyrics) - full opacity, normal style
            holder.lyricText.alpha = 1.0f
            holder.lyricText.scaleX = 1.0f
            holder.lyricText.scaleY = 1.0f
        }
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

        if (position < -1 || position >= lyricLines.size || (position != -1 && lyricLines[position].timestamp == LyricService.ATTRIBUTION_TIMESTAMP)) {
            if (highlightedPosition != -1) {
                val oldPos = highlightedPosition
                highlightedPosition = -1
                if (oldPos < lyricLines.size) notifyItemChanged(oldPos)
            }
            return
        }


        if (position == highlightedPosition) return

        val oldPosition = highlightedPosition
        highlightedPosition = position

        if (oldPosition != -1 && oldPosition < lyricLines.size) {
            notifyItemChanged(oldPosition)
        }
        if (highlightedPosition != -1) {
            notifyItemChanged(highlightedPosition)
        }

        val updateRange = 4
        if (oldPosition != -1) {
            for (i in 1..updateRange) {
                if (oldPosition - i >= 0) notifyItemChanged(oldPosition - i)
                if (oldPosition + i < lyricLines.size) notifyItemChanged(oldPosition + i)
            }
        }
        if (highlightedPosition != -1) {
            for (i in 1..updateRange) {
                if (highlightedPosition - i >= 0) notifyItemChanged(highlightedPosition - i)
                if (highlightedPosition + i < lyricLines.size) notifyItemChanged(highlightedPosition + i)
            }
        }
    }

    fun getCurrentHighlightedPosition(): Int = highlightedPosition

    fun setPlayingState(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        // Refresh the highlighted instrumental line so its animation state updates
        if (highlightedPosition in 0 until lyricLines.size &&
            lyricLines[highlightedPosition].text == instrumentalPattern) {
            notifyItemChanged(highlightedPosition)
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lyricText: TextView = itemView.findViewById(R.id.lyricLineText)
        val visualizer: InstrumentalVisualizerView = itemView.findViewById(R.id.instrumentalVisualizer)
    }
}