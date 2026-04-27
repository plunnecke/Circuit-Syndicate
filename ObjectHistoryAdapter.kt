package com.circuitsyndicate.findingtheway

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ObjectHistoryAdapter(
    private val items: MutableList<String>,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<ObjectHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.detection_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text.text = item
        holder.text.contentDescription = "Detection: $item"
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: MutableList<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
