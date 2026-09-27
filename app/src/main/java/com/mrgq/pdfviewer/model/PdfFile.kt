package com.mrgq.pdfviewer.model

data class PdfFile(
    val name: String,
    val path: String,
    val lastModified: Long = 0L,
    val size: Long = 0L,
    val pageCount: Int = 0,
    val title: String? = null,   // PDF 문서 정보 Title (없으면 null)
    val author: String? = null,  // PDF 문서 정보 Author (없으면 null)
    val cloudLabel: String? = null, // ScoreMate 에서 받은 악보면 "앙상블 · 판 2" (P05 C2) — 목록에 ☁️ 로 보인다
    val sha256: String? = null, // ScoreMate 악보면 받을 때 검증한 내용 해시 — 지휘자가 file_change 에 싣는다 (#063)
    val setlistPosition: Int? = null, // 세트리스트로 볼 때 곡 순서 (#064) — 이름 앞에 "1."
    val setlistNotes: String? = null  // 세트리스트 곡 메모 (#064)
)
