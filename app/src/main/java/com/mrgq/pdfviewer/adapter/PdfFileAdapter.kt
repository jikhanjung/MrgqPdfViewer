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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ln
import kotlin.math.pow

class PdfFileAdapter(
    private val onItemClick: (PdfFile) -> Unit,
    private val onDeleteClick: (PdfFile) -> Unit
) : ListAdapter<PdfFile, PdfFileAdapter.PdfViewHolder>(PdfDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_file, parent, false)
        return PdfViewHolder(view, onItemClick, onDeleteClick)
    }
    
    override fun onBindViewHolder(holder: PdfViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class PdfViewHolder(
        itemView: View,
        private val onItemClick: (PdfFile) -> Unit,
        private val onDeleteClick: (PdfFile) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val fileNameText: TextView = itemView.findViewById(R.id.fileNameText)
        private val fileInfoText: TextView = itemView.findViewById(R.id.fileInfoText)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
        private var currentItem: PdfFile? = null
        
        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            
            itemView.setOnClickListener {
                currentItem?.let { onItemClick(it) }
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
        
        fun bind(pdfFile: PdfFile) {
            currentItem = pdfFile
            fileNameText.text = pdfFile.name
            
            // Format file info
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val modifiedDate = dateFormat.format(Date(pdfFile.lastModified))
            val fileSize = formatFileSize(pdfFile.size)
            fileInfoText.text = "$fileSize • $modifiedDate"
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