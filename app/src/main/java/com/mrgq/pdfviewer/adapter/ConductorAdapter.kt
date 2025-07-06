package com.mrgq.pdfviewer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mrgq.pdfviewer.ConductorDiscovery
import com.mrgq.pdfviewer.R
import java.text.SimpleDateFormat
import java.util.*

class ConductorAdapter(
    private val onConductorConnect: (ConductorDiscovery.ConductorInfo) -> Unit
) : RecyclerView.Adapter<ConductorAdapter.ConductorViewHolder>() {
    
    private val conductors = mutableListOf<ConductorDiscovery.ConductorInfo>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    class ConductorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.conductorName)
        val addressText: TextView = itemView.findViewById(R.id.conductorAddress)
        val timestampText: TextView = itemView.findViewById(R.id.conductorTimestamp)
        val connectButton: Button = itemView.findViewById(R.id.connectToConductorBtn)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConductorViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conductor, parent, false)
        return ConductorViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ConductorViewHolder, position: Int) {
        val conductor = conductors[position]
        
        holder.nameText.text = conductor.name
        holder.addressText.text = "${conductor.ipAddress}:${conductor.port}"
        holder.timestampText.text = "발견: ${dateFormat.format(Date(conductor.timestamp))}"
        
        holder.connectButton.setOnClickListener {
            onConductorConnect(conductor)
        }
        
        // TV 리모컨 포커스 설정
        holder.connectButton.isFocusable = true
    }
    
    override fun getItemCount(): Int = conductors.size
    
    fun addConductor(conductor: ConductorDiscovery.ConductorInfo) {
        // 중복 체크 (IP:Port 기준)
        val existingIndex = conductors.indexOfFirst { 
            it.ipAddress == conductor.ipAddress && it.port == conductor.port 
        }
        
        if (existingIndex >= 0) {
            // 기존 항목 업데이트 (최신 timestamp로)
            conductors[existingIndex] = conductor
            notifyItemChanged(existingIndex)
        } else {
            // 새 항목 추가
            conductors.add(conductor)
            notifyItemInserted(conductors.size - 1)
        }
    }
    
    fun clearConductors() {
        val size = conductors.size
        conductors.clear()
        notifyItemRangeRemoved(0, size)
    }
    
    fun getConductorCount(): Int = conductors.size
}