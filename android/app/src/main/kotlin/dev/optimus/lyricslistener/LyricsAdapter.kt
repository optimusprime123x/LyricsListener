package dev.optimus.lyricslistener

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.optimus.lyricslistener.R
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Typeface

class LyricsAdapter(
    private val context: Context,
    private var lyricLines: List<LyricService.TimedLyricLine>
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var highlightedPosition = -1
    private var isSyncedMode = false

    // Theme colors for lyrics, initialized to defaults
    private var normalLineTextColor: Int = Color.WHITE
    private var highlightedLineTextColor: Int = Color.WHITE
    private var highlightedLineBackgroundColor: Int = Color.argb(70, 200, 200, 200)


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


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lyricLines[position]
        holder.lyricText.text = line.text

        if (isSyncedMode && position == highlightedPosition) {
            holder.itemView.setBackgroundColor(highlightedLineBackgroundColor)
            holder.lyricText.setTextColor(highlightedLineTextColor) // Should be white
            holder.lyricText.setTypeface(null, Typeface.BOLD)
            holder.lyricText.alpha = 1.0f
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.lyricText.setTextColor(normalLineTextColor) // Should be white
            holder.lyricText.setTypeface(null, Typeface.NORMAL)
            // For non-synced mode or non-highlighted synced lines, use a slightly lower alpha for a "dimmed" effect if desired
            // For now, keeping it simple with full alpha, color itself handles it for otherLinesTextColor.
            // If normalLineTextColor is pure white, and you want non-active synced lines dimmer,
            // you might pass a separate "dimmedWhite" or apply alpha here.
            // For simplicity, current design relies on 'normalLineTextColor' being appropriate (it's white).
            holder.lyricText.alpha = if (isSyncedMode) 0.85f else 1.0f // Dim non-active synced lines slightly
        }
    }

    override fun getItemCount(): Int = lyricLines.size

    fun updateLyrics(newLines: List<LyricService.TimedLyricLine>, synced: Boolean) {
        this.lyricLines = newLines
        this.isSyncedMode = synced
        this.highlightedPosition = -1
        notifyDataSetChanged()
    }

    fun setHighlight(position: Int) {
        if (!isSyncedMode) {
            if (highlightedPosition != -1) {
                val oldPos = highlightedPosition
                highlightedPosition = -1
                notifyItemChanged(oldPos)
            }
            return
        }

        if (position < -1 || position >= lyricLines.size) {
            if (position != -1) {
                if (highlightedPosition != -1) {
                     val oldPos = highlightedPosition
                     highlightedPosition = -1
                     notifyItemChanged(oldPos)
                }
            }
            return
        }

        if (position == highlightedPosition) return

        val oldPosition = highlightedPosition
        highlightedPosition = position

        if (oldPosition != -1) {
            notifyItemChanged(oldPosition)
        }
        if (highlightedPosition != -1) {
            notifyItemChanged(highlightedPosition)
        }
    }

    fun getCurrentHighlightedPosition(): Int = highlightedPosition

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lyricText: TextView = itemView.findViewById(R.id.lyricLineText)
    }
}