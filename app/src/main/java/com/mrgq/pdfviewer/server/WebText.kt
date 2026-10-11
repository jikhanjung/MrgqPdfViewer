package com.mrgq.pdfviewer.server

/** 웹 업로드 페이지의 언어 — 앱 언어 설정과 따로, 브라우저(다른 기기)의 `Accept-Language` 로 고른다 (P18 7단계). */
enum class WebLang(val tag: String) { KO("ko"), EN("en") }

/**
 * 웹 업로드 페이지 · 응답 글 (P18 7단계).
 *
 * 리소스(`R.string`)가 아니라 Kotlin 사전인 까닭: 페이지는 앱 언어가 아니라 **브라우저 언어**를 따르므로
 * 앱의 Context(지금 앱 언어로 고정된 리소스)로는 고를 수 없고, 언어별 리소스를 따로 꺼내려면
 * `createConfigurationContext` 를 요청마다 만들어야 한다. 사전이면 순수 JVM 코드라 단위 테스트로
 * 두 언어의 키가 같은지 · 페이지에 빠진 글이 없는지를 잡는다(`WebTextTest`).
 * 설정 화면에 보이는 활동 로그 줄은 앱 언어를 따르므로 그쪽은 리소스(`strings_web.xml`)다.
 *
 * 값은 화면에 보이는 글 그대로(이스케이프 전) — HTML · JS 에 넣을 때 [UploadPage] 가 이스케이프한다.
 */
object WebText {

    /**
     * `Accept-Language` → 페이지 언어. `en` 이 `ko` 보다 먼저 오거나(q 값 순, 같으면 적힌 순) `ko` 가 없으면 영어,
     * 아니면 한국어. 머리글이 없거나 비면 한국어(지금까지와 같게).
     */
    fun pickLanguage(acceptLanguage: String?): WebLang {
        if (acceptLanguage.isNullOrBlank()) return WebLang.KO
        val ranked = acceptLanguage.split(',')
            .mapIndexedNotNull { index, part ->
                val pieces = part.split(';')
                val tag = pieces[0].trim().lowercase()
                if (tag.isEmpty()) return@mapIndexedNotNull null
                val q = pieces.drop(1)
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("q=") }
                    ?.removePrefix("q=")?.trim()?.toDoubleOrNull()
                    ?: 1.0
                if (q <= 0.0) null else Triple(tag.substringBefore('-'), q, index)
            }
            .sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
        val ko = ranked.indexOf("ko")
        if (ko < 0) return WebLang.EN
        val en = ranked.indexOf("en")
        return if (en in 0 until ko) WebLang.EN else WebLang.KO
    }

    fun of(lang: WebLang): Map<String, String> = when (lang) {
        WebLang.KO -> ko
        WebLang.EN -> en
    }

    val ko: Map<String, String> = mapOf(
        "title" to "PDF 업로드",
        "heading" to "PDF 파일 관리",
        "current_files" to "현재 파일 목록",
        "sort_by_name" to "이름순",
        "sort_by_time" to "시간순",
        "delete_all" to "전체 삭제",
        "upload_files" to "파일 업로드",
        "choose_files" to "PDF 파일 선택",
        "or_drag" to "또는 파일을 여기로 드래그하세요",
        "upload" to "업로드",
        "preparing" to "업로드 준비 중...",
        "nothing_to_delete" to "❕ 삭제할 파일이 없습니다",
        "confirm_delete_all" to "모든 PDF 파일을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.",
        "deleting" to "삭제 중...",
        "all_deleted" to "🗑️ 모든 파일이 삭제되었습니다",
        "delete_all_failed" to "❌ 전체 삭제 실패",
        "delete_error" to "❌ 삭제 중 오류 발생",
        "selected_files" to "선택된 파일:",
        "uploading" to "업로드 중...",
        "processing" to "서버에서 처리 중...",
        "uploading_progress" to "업로드 중: ",
        "network_error" to "네트워크 오류",
        "upload_success" to "✅ 업로드 성공!",
        "upload_failed" to "❌ 업로드 실패: ",
        "no_files" to "업로드된 PDF 파일이 없습니다",
        "delete" to "삭제",
        "confirm_delete" to "이 파일을 삭제하시겠습니까?",
        "file_deleted" to "🗑️ 파일이 삭제되었습니다",
        "delete_failed" to "❌ 파일 삭제 실패",
        // 응답 본문(text/plain) — 실패 글은 페이지가 "업로드 실패: " 뒤에 그대로 보인다
        "resp_uploaded" to "성공적으로 업로드됨: ",
        "resp_no_pdf" to "PDF 파일이 없습니다",
        "resp_upload_error" to "업로드 중 오류 발생: ",
    )

    val en: Map<String, String> = mapOf(
        "title" to "PDF upload",
        "heading" to "PDF files",
        "current_files" to "Files on this device",
        "sort_by_name" to "By name",
        "sort_by_time" to "Newest",
        "delete_all" to "Delete all",
        "upload_files" to "Upload files",
        "choose_files" to "Choose PDF files",
        "or_drag" to "or drag files here",
        "upload" to "Upload",
        "preparing" to "Preparing upload...",
        "nothing_to_delete" to "❕ No files to delete",
        "confirm_delete_all" to "Delete all PDF files?\nThis can't be undone.",
        "deleting" to "Deleting...",
        "all_deleted" to "🗑️ All files deleted",
        "delete_all_failed" to "❌ Couldn't delete all files",
        "delete_error" to "❌ Error while deleting",
        "selected_files" to "Selected files:",
        "uploading" to "Uploading...",
        "processing" to "Processing on the device...",
        "uploading_progress" to "Uploading: ",
        "network_error" to "Network error",
        "upload_success" to "✅ Upload complete!",
        "upload_failed" to "❌ Upload failed: ",
        "no_files" to "No PDF files uploaded yet",
        "delete" to "Delete",
        "confirm_delete" to "Delete this file?",
        "file_deleted" to "🗑️ File deleted",
        "delete_failed" to "❌ Couldn't delete the file",
        "resp_uploaded" to "Uploaded: ",
        "resp_no_pdf" to "No PDF files",
        "resp_upload_error" to "Error during upload: ",
    )
}
