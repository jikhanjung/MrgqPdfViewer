package com.mrgq.pdfviewer.model

data class PdfFile(
    val name: String, // 파일 이름 — 합주에서 파일을 맞추는 데도 쓴다. 목록에 보이는 이름은 [shownName]
    val path: String,
    val lastModified: Long = 0L,
    val size: Long = 0L,
    val pageCount: Int = 0,
    val title: String? = null,   // PDF 문서 정보 Title (없으면 null)
    val author: String? = null,  // PDF 문서 정보 Author (없으면 null)
    val cloudLabel: String? = null, // ScoreMate 에서 받은 악보면 "앙상블 · 판 2" (P05 C2) — 목록에 ☁️ 로 보인다
    val sha256: String? = null, // ScoreMate 악보면 받을 때 검증한 내용 해시 — 지휘자가 file_change 에 싣는다 (#063)
    val setlistPosition: Int? = null, // 세트리스트로 볼 때 곡 순서 (#064) — 이름 앞에 "1."
    val setlistNotes: String? = null, // 세트리스트 곡 메모 (#064)
    val serverTitle: String? = null,  // ScoreMate 악보의 서버 제목 — 목록에는 파일 이름(제목 + " (파트)" + .pdf) 대신 이것만 (사용자 요청 2026-09-28)
    // ScoreMate 악보의 서버 곡 정보 — 목록 첫 줄에 제목 옆 파트, 둘째 줄에 작곡 · 편곡
    val composer: String? = null,
    val partName: String? = null,
    val arranger: String? = null,
) {
    /** 목록에 보이는 이름 — 서버 제목이 있으면 그것, 없으면 파일 이름 */
    val shownName: String get() = serverTitle?.takeIf { it.isNotBlank() } ?: name
}
