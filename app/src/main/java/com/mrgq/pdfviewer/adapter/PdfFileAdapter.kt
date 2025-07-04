package com.mrgq.pdfviewer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mrgq.pdfviewer.R
import com.mrgq.pdfviewer.model.PdfFile

class PdfFileAdapter(
    private val onItemClick: (PdfFile) -> Unit
) : ListAdapter<PdfFile, PdfFileAdapter.PdfViewHolder>(PdfDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_file, parent, false)
        return PdfViewHolder(view, onItemClick)
    }
    
    override fun onBindViewHolder(holder: PdfViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class PdfViewHolder(
        itemView: View,
        private val onItemClick: (PdfFile) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val fileNameText: TextView = itemView.findViewById(R.id.fileNameText)
        private var currentItem: PdfFile? = null
        
        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            
            itemView.setOnClickListener {
                currentItem?.let { onItemClick(it) }
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