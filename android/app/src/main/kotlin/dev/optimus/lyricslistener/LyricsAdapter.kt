package dev.optimus.lyricslistener

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.optimus.lyricslistener.R
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter(
    private val context: Context,
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
            // You can use a color from your resources: ContextCompat.getColor(context, R.color.lyric_highlight)
            holder.itemView.setBackgroundColor(Color.argb(50, 255, 255, 255)) // Semi-transparent white
            holder.lyricText.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.lyricText.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    override fun getItemCount(): Int = lyricLines.size

    fun updateLyrics(newLines: List<LyricService.TimedLyricLine>, synced: Boolean) {
        this.lyricLines = newLines
        this.isSyncedMode = synced
        this.highlightedPosition = -1 // Reset highlight
        notifyDataSetChanged()
    }

    fun setHighlight(position: Int) {
        if (!isSyncedMode || position == highlightedPosition || position < 0 || position >= lyricLines.size) {
            return // No change or invalid
        }

        val oldPosition = highlightedPosition
        highlightedPosition = position

        if (oldPosition != -1) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(highlightedPosition)
    }

    fun getCurrentHighlightedPosition(): Int = highlightedPosition

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lyricText: TextView = itemView.findViewById(R.id.lyricLineText)
    }
}