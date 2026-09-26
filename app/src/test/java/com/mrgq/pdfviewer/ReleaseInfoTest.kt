package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.update.AppVersion
import com.mrgq.pdfviewer.update.ReleaseInfo
import com.mrgq.pdfviewer.update.findSha256
import com.mrgq.pdfviewer.update.releaseNotesForDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub `releases/latest` 응답 해석과 체크섬·본문 처리 (#054). */
class ReleaseInfoTest {

    private val sha = "631f30701eec5c00944d9665b3b36b0e780b18f4c002a0021220d1923b8195c5"

    /** 실제 v0.2.2 응답에서 필요한 필드만 추린 것 */
    private fun releaseJson(assets: String) = """
        {
          "tag_name": "v0.2.2",
          "html_url": "https://github.com/jikhanjung/MrgqPdfViewer/releases/tag/v0.2.2",
          "draft": false,
          "prerelease": false,
          "body": "> 메트로놈을 **연주하면서 쓰기 편하게**\n\n### 🥁 겹박자\n- 6/8 을 `점4분음표`로",
          "assets": [$assets]
        }
    """.trimIndent()

    private val debugAsset = """
        {"name": "MrgqPdfViewer-v0.2.2-debug.apk", "size": 28029769,
         "digest": "sha256:99552dc25c5995f2a2b46a68f8cfaf1b8ab824d74f72387f2435152399f0f49b",
         "browser_download_url": "https://github.com/x/releases/download/v0.2.2/MrgqPdfViewer-v0.2.2-debug.apk"}
    """
    private val releaseAsset = """
        {"name": "MrgqPdfViewer-v0.2.2-release.apk", "size": 5990786,
         "digest": "sha256:${sha.uppercase()}",
         "browser_download_url": "https://github.com/x/releases/download/v0.2.2/MrgqPdfViewer-v0.2.2-release.apk"}
    """
    private val sumsAsset = """
        {"name": "SHA256SUMS.txt", "size": 196, "digest": null,
         "browser_download_url": "https://github.com/x/releases/download/v0.2.2/SHA256SUMS.txt"}
    """

    @Test
    fun debug_가_먼저_있어도_release_APK_를_고른다() {
        val info = ReleaseInfo.parse(releaseJson("$debugAsset, $releaseAsset, $sumsAsset"))
        assertEquals("v0.2.2", info.tag)
        assertEquals(AppVersion(0, 2, 2), info.version)
        assertEquals("MrgqPdfViewer-v0.2.2-release.apk", info.apk!!.name)
        assertEquals(5990786L, info.apk!!.size)
        assertEquals("SHA256SUMS.txt", info.checksums!!.name)
    }

    @Test
    fun digest_는_소문자_16진으로() {
        val info = ReleaseInfo.parse(releaseJson(releaseAsset))
        assertEquals(sha, info.apk!!.sha256)
    }

    @Test
    fun digest_가_없거나_null_이면_null() {
        val info = ReleaseInfo.parse(releaseJson(sumsAsset))
        assertNull(info.checksums!!.sha256)
        val noDigest = releaseAsset.replace(Regex("\"digest\": \"[^\"]+\","), "")
        assertNull(ReleaseInfo.parse(releaseJson(noDigest)).apk!!.sha256)
    }

    @Test
    fun release_APK_가_없으면_apk_는_null() {
        val info = ReleaseInfo.parse(releaseJson(debugAsset))
        assertNull(info.apk)
        assertNull(info.checksums)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 버전이_아닌_태그는_거부() {
        ReleaseInfo.parse(releaseJson("").replace("v0.2.2\"", "nightly\""))
    }

    @Test
    fun SHA256SUMS_에서_파일_해시_찾기() {
        val sums = """
            99552dc25c5995f2a2b46a68f8cfaf1b8ab824d74f72387f2435152399f0f49b  MrgqPdfViewer-v0.2.2-debug.apk
            $sha  MrgqPdfViewer-v0.2.2-release.apk
        """.trimIndent()
        assertEquals(sha, findSha256(sums, "MrgqPdfViewer-v0.2.2-release.apk"))
        // 바이너리 모드 표기(*), CRLF
        assertEquals(sha, findSha256("${sha.uppercase()} *a.apk\r\n", "a.apk"))
        assertNull(findSha256(sums, "없는.apk"))
        assertNull(findSha256("abc  a.apk", "a.apk"))  // 해시 형식 아님
    }

    @Test
    fun 본문의_마크다운_기호를_걷어낸다() {
        val text = releaseNotesForDisplay(
            "> 요약 **굵게**\n\n\n### 🥁 제목\n- 항목 `코드`\n- [링크](http://x)\n\n---\n"
        )
        assertEquals("요약 굵게\n\n🥁 제목\n• 항목 코드\n• 링크", text)
    }

    @Test
    fun 본문이_길면_자른다() {
        val text = releaseNotesForDisplay((1..30).joinToString("\n") { "줄 $it" }, maxLines = 5)
        assertTrue(text.endsWith("…"))
        assertEquals(6, text.lines().size)
        assertFalse(text.contains("줄 6"))
    }
}
