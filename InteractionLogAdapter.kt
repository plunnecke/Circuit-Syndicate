package com.circuitsyndicate.findingtheway

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class InteractionLogAdapter(
    private val onEntryClicked: (String) -> Unit
) : RecyclerView.Adapter<InteractionLogAdapter.ViewHolder>() {

    private data class LogRow(
        val displayText: String,
        val speakText: String
    )

    private val rows = mutableListOf<LogRow>()

    fun submitList(list: List<String>) {
        rows.clear()
        rows.addAll(list.map { rawEntry ->
            val formatted = formatEntry(rawEntry)
            LogRow(displayText = formatted, speakText = formatted)
        })
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.detection_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.text.text = row.displayText
        holder.text.textSize = 15f
        holder.itemView.contentDescription = row.displayText
        holder.itemView.setOnClickListener { onEntryClicked(row.speakText) }
    }

    override fun getItemCount() = rows.size

    private fun formatEntry(rawEntry: String): String {
        val tokens = mutableListOf<String>()
        var remaining = rawEntry.trim()

        while (remaining.startsWith("[")) {
            val end = remaining.indexOf(']')
            if (end <= 1) break
            tokens.add(remaining.substring(1, end))
            remaining = remaining.substring(end + 1).trimStart()
        }

        val timestamp = tokens.getOrNull(0)
        val offset = tokens.firstOrNull { it.startsWith("+") }
        val sessionToken = tokens.firstOrNull { it.startsWith("SID:") }
        val type = tokens.getOrNull(tokens.size - 2)
        val source = tokens.getOrNull(tokens.size - 1)

        val headParts = mutableListOf<String>()
        if (!timestamp.isNullOrBlank()) headParts.add(timestamp)
        if (!offset.isNullOrBlank()) headParts.add(offset)
        if (!type.isNullOrBlank()) headParts.add(type.replace('_', ' '))
        if (!source.isNullOrBlank()) headParts.add(source.replace('_', ' '))
        if (!sessionToken.isNullOrBlank()) {
            headParts.add(sessionToken.replace("SID:", "session ").replace('_', ' '))
        }

        val readableMessage = humanizeMessage(remaining)
        return if (headParts.isNotEmpty()) {
            "${headParts.joinToString(" | ")} | $readableMessage"
        } else {
            readableMessage
        }
    }

    private fun humanizeMessage(message: String): String {
        var text = message
            .replace(';', ',')
            .replace(Regex("\\s+"), " ")
            .trim()

        text = text.replace(Regex("([A-Za-z_]+)=([^, ]+)")) { match ->
            val key = match.groupValues[1]
                .replace('_', ' ')
                .lowercase(Locale.US)
            val value = match.groupValues[2].replace('_', ' ')
            "$key: $value"
        }

        text = text.replace(Regex("\\s+,"), ",")
            .replace(Regex(",\\s*"), ", ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        return text.ifBlank { "No details available." }
    }
}
