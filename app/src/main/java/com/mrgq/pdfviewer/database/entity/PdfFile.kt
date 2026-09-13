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
    val title: String? = null,           // 곡 제목 — PDF 문서 정보 Title 로 채운다 (v5~)
    val author: String? = null,          // PDF 문서 정보 Author (v5). 작곡가가 아니라 문서 작성자일 수 있어 composer 와 분리
    val composer: String? = null,        // 작곡가
    val key: String? = null,             // 조성 (C major, F# minor 등)
    val tempo: String? = null,           // 템포 (Allegro, 120 BPM 등)
    val genre: String? = null,           // 장르
    val difficulty: Int? = null,         // 난이도 (1-5)
    val docInfoReadAt: Long? = null,     // 문서 정보를 읽은 시각 (v5). null = 아직 안 읽음 (v4 이전 레코드)
    val scoreAnalyzedAt: Long? = null,   // 악보 구조(마디)를 분석한 시각 (v6). null = 아직 안 함. 마디는 score_measures

    val createdAt: Long = System.currentTimeMillis(),  // PdfAnalyzer 는 분석 당시 file.lastModified() 를 넣는다 — 파일 교체 감지에 쓴다 (PdfFileSync)
    val updatedAt: Long = System.currentTimeMillis()
)

enum class PageOrientation {
    PORTRAIT, LANDSCAPE
}
