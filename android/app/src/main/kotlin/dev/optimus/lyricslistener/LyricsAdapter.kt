package dev.optimus.lyricslistener

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter(
    private val context: Context,
    private var lyricLines: List<TimedLyricLine>
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var highlightedPosition = -1
    private var isSyncedMode = false

    // TODO: Extract hardcoded colors to colors.xml or configuration.
    private var normalLineTextColor: Int = Color.WHITE
    private var highlightedLineTextColor: Int = Color.WHITE
    private var highlightedLineBackgroundColor: Int = Color.argb(70, 200, 200, 200)
    private val attributionTextColor: Int = Color.parseColor("#A0A0A0")


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

    // TODO: Separate attribution line logic from regular lyric binding, possibly using a different ViewType.
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lyricLines[position]
        holder.lyricText.text = line.text

        if (line.timestamp == LyricService.ATTRIBUTION_TIMESTAMP) {
            // This is the attribution line, style it specially and stop.
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.lyricText.setTextColor(attributionTextColor)
            holder.lyricText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            holder.lyricText.setTypeface(null, Typeface.ITALIC)
            holder.lyricText.alpha = 0.8f
            return // IMPORTANT: Skip the rest of the highlighting logic
        }

        // Reset default appearance first
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        holder.lyricText.setTextColor(normalLineTextColor)
        holder.lyricText.setTypeface(null, Typeface.NORMAL)
        holder.lyricText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        holder.lyricText.alpha = 1.0f

        if (isSyncedMode) {
            if (position == highlightedPosition) {
                // Current highlighted line
                holder.itemView.setBackgroundColor(highlightedLineBackgroundColor)
                holder.lyricText.setTextColor(highlightedLineTextColor)
                holder.lyricText.setTypeface(null, Typeface.BOLD)
                holder.lyricText.alpha = 1.0f
            } else {
                // Other lines in synced mode (past or upcoming)
                holder.lyricText.setTextColor(normalLineTextColor)

                if (highlightedPosition != -1) { // If there is an active highlight
                    if (position < highlightedPosition) {
                        // Past lines
                        holder.lyricText.alpha = 0.60f
                    } else {
                        // Upcoming lines (position > highlightedPosition)
                        val diff = position - highlightedPosition
                        when (diff) {
                            1 -> holder.lyricText.alpha = 0.90f
                            2 -> holder.lyricText.alpha = 0.80f
                            else -> holder.lyricText.alpha = 0.70f
                        }
                    }
                } else {
                    // Synced mode, but no line is currently highlighted
                    holder.lyricText.alpha = 0.85f
                }
            }
        } else {
            // Non-synced mode (plain lyrics) - full opacity, normal style
            holder.lyricText.alpha = 1.0f
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

        val updateRange = 3
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

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lyricText: TextView = itemView.findViewById(R.id.lyricLineText)
    }
}