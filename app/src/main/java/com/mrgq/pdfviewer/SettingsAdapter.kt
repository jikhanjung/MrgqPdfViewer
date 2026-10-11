package com.mrgq.pdfviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mrgq.pdfviewer.databinding.ItemSettingsBinding

class SettingsAdapter(
    private val items: List<SettingsItem>,
    private val onItemClick: (SettingsItem) -> Unit
) : RecyclerView.Adapter<SettingsAdapter.SettingsViewHolder>() {

    class SettingsViewHolder(private val binding: ItemSettingsBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingsItem, onItemClick: (SettingsItem) -> Unit) {
            binding.iconText.text = item.icon
            binding.titleText.text = item.title
            
            if (item.subtitle.isNotEmpty()) {
                binding.subtitleText.text = item.subtitle
                binding.subtitleText.visibility = View.VISIBLE
            } else {
                binding.subtitleText.visibility = View.GONE
            }
            
            binding.arrowText.text = item.arrow
            // 태블릿 · 휴대폰은 켜기 · 끄기를 스위치로도 보인다 (P17 5단계) — TV 는 부제목 글만
            val toggle = item.checked?.takeIf { !com.mrgq.pdfviewer.utils.DeviceForm.isTv(binding.root.context) }
            binding.toggleSwitch.visibility = if (toggle != null) View.VISIBLE else View.GONE
            if (toggle != null) binding.toggleSwitch.isChecked = toggle
            binding.root.alpha = if (item.enabled) 1.0f else 0.5f
            
            binding.root.setOnClickListener {
                if (item.enabled) {
                    onItemClick(item)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingsViewHolder {
        val binding = ItemSettingsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SettingsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SettingsViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount(): Int = items.size
}