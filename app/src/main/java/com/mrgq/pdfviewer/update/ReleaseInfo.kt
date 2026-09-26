package com.mrgq.pdfviewer.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 릴리스에 올라간 파일 하나. [sha256] 은 GitHub 가 주는 digest(소문자 16진), 없으면 null. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?
)

/** GitHub `releases/latest` 응답에서 업데이트에 필요한 것만. */
data class ReleaseInfo(
    val tag: String,
    val version: AppVersion,
    val htmlUrl: String,
    val body: String,
    /** 설치할 APK — `-release.apk` 로 끝나는 에셋. debug APK 는 서명이 달라 업데이트할 수 없다 (#054 §1.3) */
    val apk: ReleaseAsset?,
    /** `SHA256SUMS.txt` — [ReleaseAsset.sha256] 이 없을 때의 대안 */
    val checksums: ReleaseAsset?
) {
    companion object {
        const val APK_SUFFIX = "-release.apk"
        const val CHECKSUMS_NAME = "SHA256SUMS.txt"

        /** 릴리스 JSON 해석. 태그가 버전 형식이 아니면 [IllegalArgumentException]. */
        fun parse(json: String): ReleaseInfo {
            val root = JsonParser.parseString(json).asJsonObject
            val tag = root.string("tag_name") ?: throw IllegalArgumentException("tag_name 없음")
            val version = AppVersion.parse(tag) ?: throw IllegalArgumentException("버전 형식이 아닌 태그: $tag")

            val assets = root.getAsJsonArray("assets")?.mapNotNull { el ->
                val a = el.asJsonObject
                val name = a.string("name") ?: return@mapNotNull null
                val url = a.string("browser_download_url") ?: return@mapNotNull null
                ReleaseAsset(
                    name = name,
                    downloadUrl = url,
                    size = a.get("size")?.takeUnless { it.isJsonNull }?.asLong ?: -1L,
                    sha256 = a.string("digest")
                        ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.lowercase()
                )
            }.orEmpty()

            return ReleaseInfo(
                tag = tag,
                version = version,
                htmlUrl = root.string("html_url").orEmpty(),
                body = root.string("body").orEmpty(),
                apk = assets.firstOrNull { it.name.endsWith(APK_SUFFIX) },
                checksums = assets.firstOrNull { it.name == CHECKSUMS_NAME }
            )
        }

        private fun JsonObject.string(key: String): String? =
            get(key)?.takeUnless { it.isJsonNull }?.asString
    }
}

/** `sha256sum` 출력 형식(`<hex>  <파일명>`, 바이너리 모드면 `<hex> *<파일명>`)에서 [fileName] 의 해시. */
fun findSha256(sumsText: String, fileName: String): String? =
    sumsText.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[1].removePrefix("*") == fileName) parts[0].lowercase() else null
        }
        .firstOrNull { it.matches(Regex("[0-9a-f]{64}")) }

/**
 * 릴리스 본문(CHANGELOG 섹션, 마크다운)을 대화상자에 보일 텍스트로. 기호만 걷어내고 줄 구조는 둔다.
 * [maxLines] 를 넘으면 자르고 "…" 를 붙인다.
 */
fun releaseNotesForDisplay(body: String, maxLines: Int = 20): String {
    val lines = body.replace("\r\n", "\n").lines()
        .map { line ->
            line.trimEnd()
                .replace(Regex("^>\\s?"), "")          // 인용
                .replace(Regex("^#{1,6}\\s+"), "")     // 제목
                .replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
                .replace("**", "")
                .replace("`", "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")  // [글](링크) → 글
        }
        .filterNot { it.trim() == "---" }
        // 빈 줄 연속은 하나로
        .fold(mutableListOf<String>()) { acc, line ->
            if (line.isBlank() && (acc.isEmpty() || acc.last().isBlank())) acc else acc.apply { add(line) }
        }
        .dropLastWhile { it.isBlank() }

    return if (lines.size <= maxLines) lines.joinToString("\n")
    else (lines.take(maxLines) + "…").joinToString("\n")
}
