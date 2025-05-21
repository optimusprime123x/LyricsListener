package dev.optimus.lyricslistener

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.optimus.lyricslistener.R
import androidx.core.content.ContextCompat // Not used currently but good for resource colors
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter(
    private val context: Context, // Keep context if needed for resources in future
    private var lyricLines: List<LyricService.TimedLyricLine>
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var highlightedPosition = -1
    private var isSyncedMode = false // To control actual highlighting behavior

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lyricLines[position]
        holder.lyricText.text = line.text

        if (isSyncedMode && position == highlightedPosition) {
            // Semi-transparent white highlight should work on most dark backgrounds
            holder.itemView.setBackgroundColor(Color.argb(60, 255, 255, 255)) // Slightly more opaque highlight
            holder.lyricText.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.lyricText.alpha = 1.0f // Full opacity for highlighted text
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.lyricText.setTypeface(null, android.graphics.Typeface.NORMAL)
            // Optionally, make non-highlighted lines slightly dimmer if many lines are visible
            // holder.lyricText.alpha = if (isSyncedMode) 0.85f else 1.0f
        }
    }

    override fun getItemCount(): Int = lyricLines.size

    fun updateLyrics(newLines: List<LyricService.TimedLyricLine>, synced: Boolean) {
        this.lyricLines = newLines
        this.isSyncedMode = synced
        val oldHighlightedPosition = highlightedPosition
        this.highlightedPosition = -1 // Reset highlight on new lyrics
        notifyDataSetChanged() // Full redraw for new data
        if (oldHighlightedPosition != -1 && oldHighlightedPosition < newLines.size && synced) {
            // If we were highlighting, try to re-highlight the same logical position if it makes sense
            // For simplicity, just reset.
        }
    }

    fun setHighlight(position: Int) {
        if (!isSyncedMode) { // Only highlight in synced mode
            if (highlightedPosition != -1) { // Clear previous highlight if existed
                val oldPos = highlightedPosition
                highlightedPosition = -1
                notifyItemChanged(oldPos)
            }
            return
        }

        // Valid range check, including -1 for clearing highlight
        if (position < -1 || position >= lyricLines.size) {
            // If position is invalid but not -1, perhaps log or handle as clear highlight
            if (position != -1) {
                if (highlightedPosition != -1) {
                     val oldPos = highlightedPosition
                     highlightedPosition = -1
                     notifyItemChanged(oldPos)
                }
                return
            }
        }
        
        if (position == highlightedPosition) return // No change

        val oldPosition = highlightedPosition
        highlightedPosition = position

        if (oldPosition != -1) {
            notifyItemChanged(oldPosition)
        }
        if (highlightedPosition != -1) { // Only notify if new position is valid
            notifyItemChanged(highlightedPosition)
        }
    }

    fun getCurrentHighlightedPosition(): Int = highlightedPosition

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lyricText: TextView = itemView.findViewById(R.id.lyricLineText)
    }
}