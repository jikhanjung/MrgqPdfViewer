package com.mrgq.pdfviewer.update

/** 릴리스에 올라간 파일 하나. [size] 는 모르면 -1, [sha256] 은 모르면 null (`SHA256SUMS.txt` 로 검증). */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long = -1L,
    val sha256: String? = null
)

/**
 * 최신 릴리스에서 업데이트에 필요한 것만. API(`api.github.com`, 비인증 IP 당 시간 60회) 대신 웹 주소
 * `github.com/<repo>/releases/latest` 가 넘겨 주는 태그만 읽고, 파일 주소는 릴리스 워크플로가 올리는 이름으로 만든다.
 */
data class ReleaseInfo(
    val tag: String,
    val version: AppVersion,
    val htmlUrl: String,
    /** 변경 내용 — 릴리스 본문과 같은 `CHANGELOG.md` 의 그 버전 섹션. 알릴 때만 따로 받는다 */
    val body: String,
    /** 설치할 APK — `-release.apk`. debug APK 는 서명이 달라 업데이트할 수 없다 (#054 §1.3) */
    val apk: ReleaseAsset?,
    /** `SHA256SUMS.txt` — APK 검증에 쓴다 */
    val checksums: ReleaseAsset?
) {
    companion object {
        const val APK_SUFFIX = "-release.apk"
        const val CHECKSUMS_NAME = "SHA256SUMS.txt"

        /** 태그로 릴리스 정보를 만든다 (`.github/workflows/build.yml` 의 APK 이름). 태그가 버전 형식이 아니면 [IllegalArgumentException] */
        fun fromTag(repoUrl: String, tag: String, body: String = ""): ReleaseInfo {
            val version = AppVersion.parse(tag) ?: throw IllegalArgumentException("버전 형식이 아닌 태그: $tag")
            val download = "$repoUrl/releases/download/$tag"
            val apkName = "MrgqPdfViewer-$tag$APK_SUFFIX"
            return ReleaseInfo(
                tag = tag,
                version = version,
                htmlUrl = "$repoUrl/releases/tag/$tag",
                body = body,
                apk = ReleaseAsset(apkName, "$download/$apkName"),
                checksums = ReleaseAsset(CHECKSUMS_NAME, "$download/$CHECKSUMS_NAME"),
            )
        }

        /** `releases/latest` 리다이렉트의 Location(`…/releases/tag/v0.2.9`)에서 태그. 릴리스가 없으면(`…/releases`) null */
        fun tagFromLocation(location: String?): String? =
            location?.substringAfter("/releases/tag/", "")
                ?.substringBefore('?')?.substringBefore('#')
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.isNotBlank() }

        /**
         * 릴리스 피드(`github.com/<repo>/releases.atom`)의 태그들 — 피드에 오는 순서대로. 웹 피드라 API 한도와 무관하고
         * 사전 릴리스도 들어 있다(neovim nightly 로 확인, 2026-10-07). ⚠️ **릴리스 없는 태그도 섞여 온다**(v0.5.3 처럼) —
         * 고른 뒤 그 릴리스에 파일이 있는지 따로 확인한다([UpdateClient])
         */
        fun tagsFromAtom(xml: String): List<String> =
            Regex("""href="[^"]*/releases/tag/([^"?#]+)"""").findAll(xml)
                .map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
                .distinct()
                .toList()

        /**
         * 업데이트 후보 태그를 높은 버전부터. 시험 태그(`-test`)는 빼고, [includePrerelease] 가 아니면 사전 릴리스(접미사)도 뺀다.
         * 사전 릴리스를 켜도 정식이 더 높으면 정식이 먼저다
         */
        fun candidates(tags: List<String>, includePrerelease: Boolean): List<String> =
            tags.mapNotNull { tag -> AppVersion.parse(tag)?.let { tag to it } }
                .filter { (_, v) -> !v.isTest && (includePrerelease || !v.isPrerelease) }
                .sortedByDescending { it.second }
                .map { it.first }

        /** `CHANGELOG.md` 에서 `## [0.2.9]` 섹션 본문 — 다음 `## ` 전까지. 없으면 빈 문자열 */
        fun changelogSection(changelog: String, version: String): String {
            val lines = changelog.replace("\r\n", "\n").lines()
            val start = lines.indexOfFirst { it.startsWith("## [${version.removePrefix("v")}]") }
            if (start < 0) return ""
            val rest = lines.drop(start + 1)
            val end = rest.indexOfFirst { it.startsWith("## ") }.let { if (it < 0) rest.size else it }
            return rest.take(end).joinToString("\n").trim()
        }
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
