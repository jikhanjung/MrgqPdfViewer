package com.mrgq.pdfviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mrgq.pdfviewer.databinding.ItemSettingsBinding

/**
 * 설정 줄 목록. [onItemFocus] 가 있으면 TV 두 칸의 왼쪽 카테고리 목록이다 — 포커스가 오면 알리고(오른쪽 미리 보기),
 * 오른쪽에 보이는 카테고리([selectedId])를 표시한다
 */
class SettingsAdapter(
    private val items: List<SettingsItem>,
    private val onItemFocus: ((SettingsItem) -> Unit)? = null,
    private val onItemClick: (SettingsItem) -> Unit,
) : RecyclerView.Adapter<SettingsAdapter.SettingsViewHolder>() {

    /** 두 칸: 오른쪽에 보이는 카테고리 */
    var selectedId: String? = null
        private set

    /** 오른쪽에 보이는 카테고리를 바꾼다 — 목록을 다시 만들지 않고 보이는 줄만 고친다(포커스를 지키려고) */
    fun select(recyclerView: RecyclerView, id: String?) {
        selectedId = id
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val position = recyclerView.getChildAdapterPosition(child)
            if (position != RecyclerView.NO_POSITION) child.isSelected = items.getOrNull(position)?.id == id
        }
    }

    inner class SettingsViewHolder(private val binding: ItemSettingsBinding) : RecyclerView.ViewHolder(binding.root) {
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
            val focus = onItemFocus
            if (focus != null) {
                binding.root.setBackgroundResource(R.drawable.settings_category_background)
                binding.root.isSelected = item.id == selectedId
                binding.root.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focus(item) }
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