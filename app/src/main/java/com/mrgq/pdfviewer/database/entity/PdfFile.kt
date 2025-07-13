package com.mrgq.pdfviewer.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_files")
data class PdfFile(
    @PrimaryKey val id: String,           // 파일 해시 또는 UUID
    val filename: String,
    val filePath: String,
    val totalPages: Int,
    
    // PDF 특성
    val orientation: PageOrientation,     // 파일 전체의 orientation
    val width: Float,                     // 첫 페이지 기준 크기
    val height: Float,
    
    // 악보 메타데이터
    val title: String? = null,           // 곡 제목
    val composer: String? = null,        // 작곡가
    val key: String? = null,             // 조성 (C major, F# minor 등)
    val tempo: String? = null,           // 템포 (Allegro, 120 BPM 등)
    val genre: String? = null,           // 장르
    val difficulty: Int? = null,         // 난이도 (1-5)
    
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class PageOrientation {
    PORTRAIT, LANDSCAPE
}