package com.example.touchblocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial

class ZoneAdapter(
    private val zones: MutableList<Zone>,
    private val onToggle: (Zone, Boolean) -> Unit,
    private val onDelete: (Zone) -> Unit
) : RecyclerView.Adapter<ZoneAdapter.ZoneViewHolder>() {

    inner class ZoneViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.zoneLabel)
        val enabledSwitch: SwitchMaterial = view.findViewById(R.id.zoneEnabledSwitch)
        val deleteButton: ImageButton = view.findViewById(R.id.zoneDeleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ZoneViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_zone, parent, false)
        return ZoneViewHolder(view)
    }

    override fun onBindViewHolder(holder: ZoneViewHolder, position: Int) {
        val zone = zones[position]
        holder.label.text = "${zone.label} (${zone.orientation.name.lowercase()}) — ${zone.width}x${zone.height}"

        // Avoid firing the listener while we're just syncing UI state.
        holder.enabledSwitch.setOnCheckedChangeListener(null)
        holder.enabledSwitch.isChecked = zone.enabled
        holder.enabledSwitch.setOnCheckedChangeListener { _, checked -> onToggle(zone, checked) }

        holder.deleteButton.setOnClickListener { onDelete(zone) }
    }

    override fun getItemCount(): Int = zones.size

    fun updateZones(newZones: List<Zone>) {
        zones.clear()
        zones.addAll(newZones)
        notifyDataSetChanged()
    }

    /** Current in-memory list, including any in-place mutations (e.g. enabled toggles). */
    fun currentZones(): List<Zone> = zones
}
