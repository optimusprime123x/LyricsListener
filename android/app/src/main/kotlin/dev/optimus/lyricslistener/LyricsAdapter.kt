package dev.optimus.lyricslistener

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.optimus.lyricslistener.R

class LyricsAdapter(
    private val context: Context,
    private var lyricLines: List<LyricService.TimedLyricLine>
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var highlightedPosition = -1
    private var isSyncedMode = false

    private var normalLineTextColor: Int = Color.WHITE
    private var highlightedLineTextColor: Int = Color.WHITE // Often same as normal for lyrics
    private var highlightedLineBackgroundColor: Int = Color.argb(70, 200, 200, 200)


    fun updateThemeColors(
        normalLineTextColor: Int,
        highlightedLineTextColor: Int,
        highlightedLineBackgroundColor: Int
    ) {
        this.normalLineTextColor = normalLineTextColor
        this.highlightedLineTextColor = highlightedLineTextColor
        this.highlightedLineBackgroundColor = highlightedLineBackgroundColor
        notifyDataSetChanged() // Redraw all visible items with new theme
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lyricLines[position]
        holder.lyricText.text = line.text

        // Reset default appearance
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        holder.lyricText.setTextColor(normalLineTextColor)
        holder.lyricText.setTypeface(null, Typeface.NORMAL)
        // holder.lyricText.textSize = 18f // Assuming 18sp is default from XML.
                                         // Avoid frequent textSize changes if possible for performance.
                                         // Alpha and Typeface are usually sufficient.

        if (isSyncedMode) {
            if (position == highlightedPosition) {
                // Current highlighted line
                holder.itemView.setBackgroundColor(highlightedLineBackgroundColor)
                holder.lyricText.setTextColor(highlightedLineTextColor) // Usually same as normal for lyrics
                holder.lyricText.setTypeface(null, Typeface.BOLD)
                holder.lyricText.alpha = 1.0f
            } else {
                // Other lines in synced mode (past or upcoming)
                holder.lyricText.setTextColor(normalLineTextColor) // Ensure it's the base color

                if (highlightedPosition != -1) { // If there is an active highlight
                    if (position < highlightedPosition) {
                        // Past lines
                        holder.lyricText.alpha = 0.60f // Dimmer
                        // Optional: holder.lyricText.setTypeface(null, Typeface.ITALIC)
                    } else {
                        // Upcoming lines (position > highlightedPosition)
                        val diff = position - highlightedPosition
                        when (diff) {
                            1 -> holder.lyricText.alpha = 0.90f // Next line, slightly dimmed
                            2 -> holder.lyricText.alpha = 0.80f // Line after next
                            else -> holder.lyricText.alpha = 0.70f // Further upcoming lines
                        }
                    }
                } else {
                    // Synced mode, but no line is currently highlighted (e.g., before song starts)
                    holder.lyricText.alpha = 0.85f // Default for non-active synced lines
                }
            }
        } else {
            // Non-synced mode (plain lyrics) - full opacity, normal style
            holder.lyricText.alpha = 1.0f
        }
    }

    override fun getItemCount(): Int = lyricLines.size

    fun updateLyrics(newLines: List<LyricService.TimedLyricLine>, synced: Boolean) {
        this.lyricLines = newLines
        this.isSyncedMode = synced
        val oldHighlight = highlightedPosition
        this.highlightedPosition = -1 // Reset highlight on new lyrics
        if (oldHighlight != -1 && oldHighlight < lyricLines.size) { // oldHighlight could be out of bounds for new shorter list
            // No, don't notify item changed for old highlight as list is entirely new.
        }
        notifyDataSetChanged()
    }

    fun setHighlight(position: Int) {
        if (!isSyncedMode && highlightedPosition != -1) { // Clear highlight if switching from synced to non-synced with an active highlight
            val oldPos = highlightedPosition
            highlightedPosition = -1
            notifyItemChanged(oldPos)
            return
        }
        if (!isSyncedMode) return // No highlighting for non-synced mode

        if (position < -1 || position >= lyricLines.size) {
             // Invalid position, ensure current highlight is cleared if it was set
            if (highlightedPosition != -1) {
                val oldPos = highlightedPosition
                highlightedPosition = -1
                notifyItemChanged(oldPos)
            }
            return
        }

        if (position == highlightedPosition) return // No change

        val oldPosition = highlightedPosition
        highlightedPosition = position

        // Animate changes by notifying specific items
        if (oldPosition != -1 && oldPosition < lyricLines.size) { // Check bounds for oldPosition after list update
            notifyItemChanged(oldPosition)
        }
        if (highlightedPosition != -1) { // New position is valid
            notifyItemChanged(highlightedPosition)
        }

        // Also update items around the new/old highlight for the "pull-up" effect
        // This makes the alpha changes smoother for nearby lines
        val updateRange = 3 // Number of lines above/below to also refresh
        if (oldPosition != -1 && oldPosition < lyricLines.size) {
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

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lyricText: TextView = itemView.findViewById(R.id.lyricLineText)
    }
}