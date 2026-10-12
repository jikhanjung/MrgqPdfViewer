package com.mrgq.pdfviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mrgq.pdfviewer.databinding.ItemSettingsBinding
import com.mrgq.pdfviewer.databinding.ItemSettingsButtonBinding
import com.mrgq.pdfviewer.databinding.ItemSettingsHeaderBinding
import com.mrgq.pdfviewer.databinding.ItemSettingsRowBinding

/**
 * 설정 줄 목록. [selectable] 이면 두 칸의 왼쪽 카테고리 목록이다 — 오른쪽에 보이는 카테고리([selectedId])를 표시한다.
 * [detail] 이면 오른쪽(상세) 목록 — Android 설정처럼 카드 없는 평평한 줄 · 묶음 제목([SettingsType.HEADER]), 아이콘 없음 (#122),
 * 누르면 바로 하는 줄은 단추 모양([SettingsType.BUTTON], #123).
 * [onItemFocus] 는 TV 에서 포커스가 온 줄을 알린다(오른쪽 미리 보기)
 */
class SettingsAdapter(
    private val items: List<SettingsItem>,
    private val selectable: Boolean = false,
    private val detail: Boolean = false,
    private val onItemFocus: ((SettingsItem) -> Unit)? = null,
    private val onItemClick: (SettingsItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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

    /** 줄 하나를 그린다 — 카드 줄(왼쪽 · 한 칸 목록)과 평평한 줄(오른쪽)이 같은 뷰 묶음을 쓴다 */
    private inner class RowViews(
        val root: View,
        val title: TextView,
        val arrow: TextView,
        val toggle: SwitchMaterial,
        val icon: TextView?,
    ) {
        fun bind(item: SettingsItem) {
            icon?.text = item.icon
            title.text = item.title
            // 오른쪽: 지금 값이 있으면 값, 아니면 화살표 (#127)
            val right = item.value.ifEmpty { item.arrow }
            arrow.text = right
            arrow.visibility = if (right.isNotEmpty()) View.VISIBLE else View.GONE
            // 켜기 · 끄기는 스위치로도 — 카드 줄은 터치 기기만(P17 5단계), 평평한 줄은 TV 도(Android TV 설정처럼, #122)
            val showToggle = item.checked != null && (detail || !com.mrgq.pdfviewer.utils.DeviceForm.isTv(root.context))
            toggle.visibility = if (showToggle) View.VISIBLE else View.GONE
            if (showToggle) toggle.isChecked = item.checked == true
            root.alpha = if (item.enabled) 1.0f else if (detail) 0.38f else 0.5f
            root.setOnClickListener { if (item.enabled) onItemClick(item) }
            if (selectable) {
                root.setBackgroundResource(R.drawable.settings_category_background)
                root.isSelected = item.id == selectedId
            }
            val focus = onItemFocus
            if (focus != null) root.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focus(item) }
        }
    }

    private inner class RowHolder(val views: RowViews) : RecyclerView.ViewHolder(views.root)
    private class HeaderHolder(val binding: ItemSettingsHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    /** 단추 줄 (#123) — 오른쪽 목록에서만. 왼쪽 · 한 칸 카드 목록에는 보통 줄로 */
    private inner class ButtonHolder(val binding: ItemSettingsButtonBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingsItem) {
            binding.buttonText.text = item.title
            binding.root.alpha = if (item.enabled) 1.0f else 0.38f
            binding.root.setOnClickListener { if (item.enabled) onItemClick(item) }
            val focus = onItemFocus
            if (focus != null) binding.root.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focus(item) }
        }
    }

    override fun getItemViewType(position: Int): Int = when {
        items[position].type == SettingsType.HEADER -> TYPE_HEADER
        detail && items[position].type == SettingsType.BUTTON -> TYPE_BUTTON
        detail -> TYPE_ROW
        else -> TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemSettingsHeaderBinding.inflate(inflater, parent, false))
            TYPE_BUTTON -> ButtonHolder(ItemSettingsButtonBinding.inflate(inflater, parent, false))
            TYPE_ROW -> ItemSettingsRowBinding.inflate(inflater, parent, false).let {
                RowHolder(RowViews(it.root, it.titleText, it.arrowText, it.toggleSwitch, icon = null))
            }
            else -> ItemSettingsBinding.inflate(inflater, parent, false).let {
                RowHolder(RowViews(it.root, it.titleText, it.arrowText, it.toggleSwitch, it.iconText))
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderHolder -> holder.binding.headerText.text = item.title
            is ButtonHolder -> holder.bind(item)
            is RowHolder -> holder.views.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private const val TYPE_CARD = 0
        private const val TYPE_ROW = 1
        private const val TYPE_HEADER = 2
        private const val TYPE_BUTTON = 3
    }
}
