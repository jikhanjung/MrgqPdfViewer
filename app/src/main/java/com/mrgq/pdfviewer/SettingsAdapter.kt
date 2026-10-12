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
            icon?.text = item.iconRes?.let { iconSpan(icon.context, it) } ?: item.icon
            // 검은 그림 이모지는 회색 동그라미 위에서 안 보여 밝은 동그라미로
            icon?.setBackgroundResource(
                if (item.icon in DARK_GLYPH_ICONS) R.drawable.elegant_icon_background_light else R.drawable.elegant_icon_background
            )
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

    /** [position] 줄 아래에 구분줄을 긋나 — 줄과 줄 사이만. 묶음 제목의 위 · 아래와 마지막 줄 아래는 긋지 않는다 */
    fun dividerBelow(position: Int): Boolean =
        items.getOrNull(position)?.type?.let { it != SettingsType.HEADER } == true &&
            items.getOrNull(position + 1)?.type?.let { it != SettingsType.HEADER } == true

    /**
     * 오른쪽(상세) 목록의 줄 사이 구분줄 — Android 설정처럼 가는 선 (#129). 글 시작(28dp)부터 오른쪽 끝 안쪽까지.
     * 줄 자리를 [DIVIDER_DP] 만큼 띄워 포커스 면과 겹치지 않게 한다
     */
    class Divider(context: android.content.Context) : RecyclerView.ItemDecoration() {
        private val density = context.resources.displayMetrics.density
        private val height = (DIVIDER_DP * density).toInt().coerceAtLeast(1)
        private val inset = (28 * density).toInt()
        private val paint = android.graphics.Paint().apply { color = DIVIDER_COLOR }

        private fun below(parent: RecyclerView, view: View): Boolean {
            val position = parent.getChildAdapterPosition(view)
            return position != RecyclerView.NO_POSITION && (parent.adapter as? SettingsAdapter)?.dividerBelow(position) == true
        }

        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.set(0, 0, 0, if (below(parent, view)) height else 0)
        }

        override fun onDraw(canvas: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (!below(parent, child)) continue
                val top = child.bottom + child.translationY
                canvas.drawRect(
                    (parent.paddingLeft + inset).toFloat(), top,
                    (parent.width - parent.paddingRight - inset).toFloat(), top + height,
                    paint,
                )
            }
        }
    }

    companion object {
        private const val TYPE_CARD = 0
        private const val TYPE_ROW = 1
        private const val TYPE_HEADER = 2
        private const val TYPE_BUTTON = 3
        /** 구분줄 굵기 · 색 — 어두운 바탕에서 보일 듯 말 듯 (Android 설정의 outlineVariant 쯤) */
        private const val DIVIDER_DP = 1f
        private const val DIVIDER_COLOR = 0x26FFFFFF
        /** 그림이 검은 이모지 — 메트로놈 🎵 */
        private val DARK_GLYPH_ICONS = setOf("🎵")
        /** 벡터 그림 아이콘의 폭 — 이모지 동그라미(32dp) 안에 */
        private const val ICON_RES_WIDTH_DP = 20f

        /** 그림을 글 한 칸으로 — 동그라미 TextView 의 가운데 맞춤을 그대로 쓴다 */
        private fun iconSpan(context: android.content.Context, @androidx.annotation.DrawableRes res: Int): CharSequence {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, res) ?: return ""
            val width = (ICON_RES_WIDTH_DP * context.resources.displayMetrics.density).toInt()
            val height = width * drawable.intrinsicHeight / maxOf(1, drawable.intrinsicWidth)
            drawable.setBounds(0, 0, width, height)
            return android.text.SpannableString(" ").apply {
                setSpan(android.text.style.ImageSpan(drawable, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
