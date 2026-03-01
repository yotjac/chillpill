package com.chillpill.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chillpill.app.databinding.ItemAppPickerBinding

class AppPickerAdapter(
    private val apps: List<AppItem>,
    initialBlacklist: Set<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private var filteredApps: List<AppItem> = apps
    private val selected = initialBlacklist.toMutableSet()

    fun getSelectedPackages(): Set<String> = selected.toSet()

    fun filter(query: String) {
        filteredApps = if (query.isBlank()) apps
        else apps.filter { it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
        notifyDataSetChanged()
    }

    fun selectAll() {
        filteredApps.forEach { selected.add(it.packageName) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectNone() {
        filteredApps.forEach { selected.remove(it.packageName) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAppPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = filteredApps[position]
        holder.binding.itemIcon.setImageDrawable(item.icon)
        holder.binding.itemLabel.text = item.label
        holder.binding.itemCheckbox.isChecked = item.packageName in selected

        holder.binding.root.setOnClickListener {
            if (item.packageName in selected) selected.remove(item.packageName)
            else selected.add(item.packageName)
            holder.binding.itemCheckbox.isChecked = item.packageName in selected
            onSelectionChanged()
        }
        holder.binding.itemCheckbox.setOnClickListener {
            if (holder.binding.itemCheckbox.isChecked) selected.add(item.packageName)
            else selected.remove(item.packageName)
            onSelectionChanged()
        }
    }

    override fun getItemCount(): Int = filteredApps.size

    class VH(val binding: ItemAppPickerBinding) : RecyclerView.ViewHolder(binding.root)
}
