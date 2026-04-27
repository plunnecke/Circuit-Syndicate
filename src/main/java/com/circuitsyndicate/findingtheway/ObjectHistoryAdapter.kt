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
        val detectionTextView: TextView = view.findViewById(R.id.detection_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val rowView = inflater.inflate(R.layout.item_history, parent, false)
        return ViewHolder(rowView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val currentDetection = items[position]
        holder.detectionTextView.text = currentDetection
        holder.detectionTextView.contentDescription = "Detection: $currentDetection"
        holder.itemView.setOnClickListener { onItemClick(currentDetection) }
    }

    override fun getItemCount(): Int {
        return items.size
    }

    fun updateData(updatedItems: MutableList<String>) {
        items.clear()
        items.addAll(updatedItems)
        notifyDataSetChanged()
    }
}
