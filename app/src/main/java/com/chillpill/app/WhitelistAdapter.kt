package com.chillpill.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chillpill.app.databinding.ItemWhitelistAppBinding

class WhitelistAdapter(
    private val prefs: Prefs,
    private var apps: List<AppItem>,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<WhitelistAdapter.VH>() {

    fun updateApps(newApps: List<AppItem>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemWhitelistAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = apps[position]
        holder.binding.itemAppIcon.setImageDrawable(item.icon)
        holder.binding.itemAppName.text = item.label
        holder.binding.itemRemove.setOnClickListener { onRemove(item.packageName) }
    }

    override fun getItemCount(): Int = apps.size

    class VH(val binding: ItemWhitelistAppBinding) : RecyclerView.ViewHolder(binding.root)
}
