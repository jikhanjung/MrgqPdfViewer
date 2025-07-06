package com.mrgq.pdfviewer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mrgq.pdfviewer.R
import com.mrgq.pdfviewer.model.SettingsItem
import com.mrgq.pdfviewer.model.SettingsItemType

class SettingsAdapter(
    private val onItemClick: (SettingsItem) -> Unit
) : ListAdapter<SettingsItem, SettingsAdapter.ViewHolder>(SettingsDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconText: TextView = itemView.findViewById(R.id.iconText)
        private val titleText: TextView = itemView.findViewById(R.id.titleText)
        private val subtitleText: TextView = itemView.findViewById(R.id.subtitleText)
        private val arrowText: TextView = itemView.findViewById(R.id.arrowText)
        
        fun bind(item: SettingsItem) {
            iconText.text = item.icon
            titleText.text = item.title
            
            if (item.subtitle.isNotEmpty()) {
                subtitleText.text = item.subtitle
                subtitleText.visibility = View.VISIBLE
            } else {
                subtitleText.visibility = View.GONE
            }
            
            arrowText.visibility = if (item.hasArrow) View.VISIBLE else View.GONE
            
            // 아이템 타입에 따른 스타일 설정
            when (item.type) {
                SettingsItemType.CATEGORY -> {
                    titleText.setTextColor(itemView.context.getColor(R.color.tv_text_primary))
                    arrowText.text = "▶"
                }
                SettingsItemType.ACTION -> {
                    titleText.setTextColor(itemView.context.getColor(R.color.tv_primary))
                    arrowText.text = ""
                }
                SettingsItemType.TOGGLE -> {
                    titleText.setTextColor(itemView.context.getColor(R.color.tv_text_primary))
                    arrowText.text = "⚪" // 토글 상태에 따라 변경 가능
                }
                SettingsItemType.INPUT -> {
                    titleText.setTextColor(itemView.context.getColor(R.color.tv_text_primary))
                    arrowText.text = "✏️"
                }
                SettingsItemType.INFO -> {
                    titleText.setTextColor(itemView.context.getColor(R.color.tv_text_secondary))
                    arrowText.text = ""
                }
            }
            
            // 활성/비활성 상태 설정
            itemView.alpha = if (item.isEnabled) 1.0f else 0.5f
            itemView.isClickable = item.isEnabled
            itemView.isFocusable = item.isEnabled
            
            if (item.isEnabled) {
                itemView.setOnClickListener { onItemClick(item) }
            } else {
                itemView.setOnClickListener(null)
            }
        }
    }
    
    class SettingsDiffCallback : DiffUtil.ItemCallback<SettingsItem>() {
        override fun areItemsTheSame(oldItem: SettingsItem, newItem: SettingsItem): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: SettingsItem, newItem: SettingsItem): Boolean {
            return oldItem == newItem
        }
    }
}