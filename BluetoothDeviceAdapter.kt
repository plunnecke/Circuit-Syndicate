package com.circuitsyndicate.findingtheway

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.RecyclerView

class BluetoothDeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onConnect: (BluetoothDevice, DeviceType) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView    = view.findViewById(R.id.device_name)
        val address: TextView = view.findViewById(R.id.device_address)
        val btnGlasses: Button = view.findViewById(R.id.btn_connect_glasses)
        val btnVest: Button    = view.findViewById(R.id.btn_connect_vest)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        val ctx = holder.itemView.context

        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return

        val displayName = device.name ?: "Unknown Device"
        holder.name.text = displayName
        holder.address.text = device.address

        holder.btnGlasses.contentDescription = "Connect $displayName as Smart Glasses"
        holder.btnVest.contentDescription = "Connect $displayName as Navigation Vest"

        holder.btnGlasses.setOnClickListener { onConnect(device, DeviceType.GLASSES) }
        holder.btnVest.isEnabled = true
        holder.btnVest.alpha = 1f
        holder.btnVest.setOnClickListener { onConnect(device, DeviceType.VEST) }
    }

    override fun getItemCount() = devices.size
}
