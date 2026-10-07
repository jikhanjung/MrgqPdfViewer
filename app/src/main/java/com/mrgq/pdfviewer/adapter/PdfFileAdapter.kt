package com.mrgq.pdfviewer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mrgq.pdfviewer.R
import com.mrgq.pdfviewer.model.PdfFile
import com.mrgq.pdfviewer.utils.PdfDocumentInfo
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ln
import kotlin.math.pow

class PdfFileAdapter(
    private val onItemClick: (PdfFile, Int) -> Unit,
    private val onDeleteClick: (PdfFile) -> Unit
) : ListAdapter<PdfFile, PdfFileAdapter.PdfViewHolder>(PdfDiffCallback()) {
    
    private var isFileManagementMode = false
    
    init {
        // Enable stable IDs for better RecyclerView performance and consistency
        setHasStableIds(true)
    }
    
    override fun getItemId(position: Int): Long {
        // Use the file's path to generate a stable, unique ID
        return getItem(position).path.hashCode().toLong()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_file, parent, false)
        if (com.mrgq.pdfviewer.utils.DeviceForm.isPhone(parent.context)) compactForPhone(view)
        return PdfViewHolder(view, onItemClick, onDeleteClick)
    }
    
    /** 휴대폰: 오른쪽 크기 · 쪽 수 · 날짜가 이름 칸을 좁혀 → 이름 아래 줄로 옮기고 카드 바깥 여백을 줄인다 (2026-10-05) */
    private fun compactForPhone(card: View) {
        val dp = card.resources.displayMetrics.density
        (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginStart = (4 * dp).toInt(); it.marginEnd = (4 * dp).toInt() }
        card.setPadding((14 * dp).toInt(), card.paddingTop, (10 * dp).toInt(), card.paddingBottom)
        val info = card.findViewById<View>(R.id.fileInfoText)
        val column = card.findViewById<View>(R.id.fileNameText).parent as ViewGroup
        (info.parent as ViewGroup).removeView(info)
        column.addView(info, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (2 * dp).toInt() })
    }

    override fun onBindViewHolder(holder: PdfViewHolder, position: Int) {
        holder.bind(getItem(position), position, isFileManagementMode)
    }
    
    fun setFileManagementMode(enabled: Boolean) {
        isFileManagementMode = enabled
        notifyDataSetChanged()
    }
    
    class PdfViewHolder(
        itemView: View,
        private val onItemClick: (PdfFile, Int) -> Unit,
        private val onDeleteClick: (PdfFile) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val fileNameText: TextView = itemView.findViewById(R.id.fileNameText)
        private val fileInfoText: TextView = itemView.findViewById(R.id.fileInfoText)
        private val docInfoText: TextView = itemView.findViewById(R.id.docInfoText)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
        private var currentItem: PdfFile? = null
        private var currentPosition: Int = -1
        
        init {
            // 리모컨(TV)은 포커스로 고르고 OK 로 연다. 터치(태블릿)에서 포커스를 받으면 첫 탭이 포커스에 쓰여 두 번 눌러야 열린다 —
            // 터치 모드에서는 포커스를 받지 않게 해 한 번에 연다 (사용자 요청 2026-09-28)
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false
            
            itemView.setOnClickListener {
                currentItem?.let { onItemClick(it, currentPosition) }
            }
            
            deleteButton.setOnClickListener {
                currentItem?.let { onDeleteClick(it) }
            }
            
            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.05f).scaleY(1.05f).duration = 200
                    view.elevation = 8f
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).duration = 200
                    view.elevation = 0f
                }
            }
        }
        
        fun bind(pdfFile: PdfFile, position: Int, isFileManagementMode: Boolean) {
            currentItem = pdfFile
            currentPosition = position
            // 세트리스트로 볼 때는 곡 순서를 앞에 (#064). ScoreMate 악보면 제목 옆에 파트(Full Score 등)를 작고 흐리게 (사용자 요청 2026-09-28)
            val title = pdfFile.setlistPosition?.let { "$it. ${pdfFile.shownName}" } ?: pdfFile.shownName
            val part = pdfFile.partName?.takeIf { it.isNotBlank() && pdfFile.serverTitle != null }
            fileNameText.text = android.text.SpannableStringBuilder(title).apply {
                fun small(text: String, color: Int) {
                    val start = length
                    append(text)
                    setSpan(android.text.style.RelativeSizeSpan(0.72f), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(color), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (part != null) small("   $part", 0xFFB0B0B0.toInt())
                // 악보 메모 (P11) — 있으면 ✏️, 서버에 올리지 못한 것이 있으면 "동기화 필요" (사용자 요청 2026-10-08)
                if (pdfFile.notesUnsent) small("   ✏️ 동기화 필요", 0xFFFFB74D.toInt())
                else if (pdfFile.hasNotes) small("   ✏️", 0xFFB0B0B0.toInt())
            }

            // 둘째 줄 — ScoreMate 악보면 "작곡 ○○ · 편곡 ○○"(서버 곡 정보), 아니면 PDF 문서 정보(제목 · 작성자). 없으면 줄을 숨긴다
            val serverLine = if (pdfFile.serverTitle != null) {
                listOfNotNull(
                    pdfFile.composer?.takeIf { it.isNotBlank() }?.let { "작곡 $it" },
                    pdfFile.arranger?.takeIf { it.isNotBlank() }?.let { "편곡 $it" },
                ).joinToString(" · ").takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val subtitle = if (pdfFile.serverTitle != null) serverLine
            else PdfDocumentInfo.subtitle(pdfFile.shownName, pdfFile.title, pdfFile.author)
            docInfoText.text = subtitle ?: ""
            docInfoText.visibility = if (subtitle != null) View.VISIBLE else View.GONE
            
            // Format file info
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val modifiedDate = dateFormat.format(Date(pdfFile.lastModified))
            val fileSize = formatFileSize(pdfFile.size)
            val pageInfo = if (pdfFile.pageCount > 0) "${pdfFile.pageCount}페이지" else "페이지 수 알 수 없음"
            // ScoreMate 악보여도 ☁️ · 앙상블 · 파트 · 판은 싣지 않는다 — 서재가 이미 ScoreMate 이고 파트는 제목 옆에 (사용자 요청 2026-09-28)
            val notes = pdfFile.setlistNotes?.takeIf { it.isNotBlank() }?.let { "📝 $it • " } ?: ""
            fileInfoText.text = "$notes$fileSize • $pageInfo • $modifiedDate"
            
            // 파일 관리 모드에 따라 삭제 버튼 표시/숨김
            deleteButton.visibility = if (isFileManagementMode) View.VISIBLE else View.GONE
        }
        
        private fun formatFileSize(bytes: Long): String {
            if (bytes == 0L) return "0 B"
            val k = 1024
            val sizes = arrayOf("B", "KB", "MB", "GB")
            val i = (ln(bytes.toDouble()) / ln(k.toDouble())).toInt()
            return "%.1f %s".format(bytes / k.toDouble().pow(i.toDouble()), sizes[i])
        }
    }
    
    class PdfDiffCallback : DiffUtil.ItemCallback<PdfFile>() {
        override fun areItemsTheSame(oldItem: PdfFile, newItem: PdfFile): Boolean {
            return oldItem.path == newItem.path
        }
        
        override fun areContentsTheSame(oldItem: PdfFile, newItem: PdfFile): Boolean {
            return oldItem == newItem
        }
    }
}