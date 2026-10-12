package com.mrgq.pdfviewer.scoremate

/**
 * 사용자 설명서 주소 (P19 4단계 · P20) — ScoreMate 서버가 판마다 보관한 설명서를 HTML 로 보인다.
 * 이 판의 설명서가 없거나 그 언어가 없으면 서버가 정식 최신 · 한국어로 보인다.
 */
object ManualLink {

    /** [language] 는 화면 글 언어(`en` 이면 영어, 그 밖은 한국어), [versionName] 은 `BuildConfig.VERSION_NAME`(v 없이) */
    fun url(server: String, language: String, versionName: String): String {
        val lang = if (language.startsWith("en")) "en" else "ko"
        return "${server.trimEnd('/')}/manual/?lang=$lang&v=v$versionName"
    }
}
