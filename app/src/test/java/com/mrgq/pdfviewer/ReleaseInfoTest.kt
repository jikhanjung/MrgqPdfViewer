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

/** 최신 릴리스(웹 `releases/latest` 리다이렉트 · CHANGELOG) 해석과 체크섬·본문 처리 (#054). */
class ReleaseInfoTest {

    private val sha = "631f30701eec5c00944d9665b3b36b0e780b18f4c002a0021220d1923b8195c5"
    private val repo = "https://github.com/jikhanjung/MrgqPdfViewer"

    @Test
    fun 태그로_release_APK_와_SHA256SUMS_주소를_만든다() {
        val info = ReleaseInfo.fromTag(repo, "v0.2.9")
        assertEquals("v0.2.9", info.tag)
        assertEquals(AppVersion(0, 2, 9), info.version)
        assertEquals("MrgqPdfViewer-v0.2.9-release.apk", info.apk!!.name)
        assertEquals("$repo/releases/download/v0.2.9/MrgqPdfViewer-v0.2.9-release.apk", info.apk!!.downloadUrl)
        assertEquals("$repo/releases/download/v0.2.9/SHA256SUMS.txt", info.checksums!!.downloadUrl)
        assertNull(info.apk!!.sha256)  // SHA256SUMS.txt 로 검증
        assertEquals("$repo/releases/tag/v0.2.9", info.htmlUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 버전이_아닌_태그는_거부() {
        ReleaseInfo.fromTag(repo, "nightly")
    }

    @Test
    fun latest_리다이렉트에서_태그() {
        assertEquals("v0.2.9", ReleaseInfo.tagFromLocation("$repo/releases/tag/v0.2.9"))
        assertEquals("v1.0.0-rc1", ReleaseInfo.tagFromLocation("$repo/releases/tag/v1.0.0-rc1?x=1"))
        assertNull(ReleaseInfo.tagFromLocation("$repo/releases"))  // 릴리스 없음
        assertNull(ReleaseInfo.tagFromLocation(null))
    }

    @Test
    fun CHANGELOG_에서_그_버전_섹션만() {
        val changelog = """
            # 변경 이력

            ## [Unreleased]

            ---

            ## [0.2.9] - 2026-09-28

            > 요약

            ### 🎼 세트리스트
            - 항목

            ---

            ## [0.2.8] - 2026-09-28
            - 이전
        """.trimIndent()
        assertEquals("> 요약\n\n### 🎼 세트리스트\n- 항목\n\n---", ReleaseInfo.changelogSection(changelog, "v0.2.9"))
        assertEquals("- 이전", ReleaseInfo.changelogSection(changelog, "0.2.8"))
        assertEquals("", ReleaseInfo.changelogSection(changelog, "v9.9.9"))
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

    // ── 사전 릴리스 받기 — 릴리스 피드 ──

    private val atom = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <link type="text/html" rel="alternate" href="https://github.com/jikhanjung/MrgqPdfViewer/releases"/>
  <entry><link rel="alternate" type="text/html" href="https://github.com/jikhanjung/MrgqPdfViewer/releases/tag/v0.6.0-beta.1"/><title>v0.6.0-beta.1</title></entry>
  <entry><link rel="alternate" type="text/html" href="https://github.com/jikhanjung/MrgqPdfViewer/releases/tag/v0.5.7"/><title>v0.5.7</title></entry>
  <entry><link rel="alternate" type="text/html" href="https://github.com/jikhanjung/MrgqPdfViewer/releases/tag/v0.5.7-test"/></entry>
  <entry><link rel="alternate" type="text/html" href="https://github.com/jikhanjung/MrgqPdfViewer/releases/tag/v0.5.6"/></entry>
</feed>"""

    @Test
    fun 피드에서_태그를_읽는다() {
        assertEquals(listOf("v0.6.0-beta.1", "v0.5.7", "v0.5.7-test", "v0.5.6"), ReleaseInfo.tagsFromAtom(atom))
    }

    @Test
    fun 후보는_높은_버전부터_시험_태그는_빼고() {
        val tags = ReleaseInfo.tagsFromAtom(atom)
        assertEquals(listOf("v0.6.0-beta.1", "v0.5.7", "v0.5.6"), ReleaseInfo.candidates(tags, includePrerelease = true))
        assertEquals(listOf("v0.5.7", "v0.5.6"), ReleaseInfo.candidates(tags, includePrerelease = false))
        // 정식이 더 높으면 정식이 먼저
        assertEquals("v0.6.0", ReleaseInfo.candidates(tags + "v0.6.0", includePrerelease = true).first())
        assertEquals(emptyList<String>(), ReleaseInfo.candidates(listOf("nightly", "latest"), includePrerelease = true))
    }

    // ── ScoreMate 서버 (P15 §2.2, #118) ──

    private val server = "https://scoremate.noematica.kr"
    private val serverSha = "6b64cf56840989a5a54d0bac2176f4bed93689872625767287631679b842e26c"

    @Test
    fun 서버_응답을_읽는다() {
        val json = """
            {"version":"v0.7.6-beta.1","prerelease":true,"apk":"MrgqPdfViewer-v0.7.6-beta.1-release.apk","size":6676762,
             "sha256":"${serverSha.uppercase()}","version_code":90,"published_at":"2026-10-11T15:18:07+09:00",
             "notes":"> 요약\n- 항목","download_url":"$server/api/v1/app-releases/android/v0.7.6-beta.1/apk/"}
        """.trimIndent()
        val info = ReleaseInfo.fromScoreMate(json, server)
        assertEquals("v0.7.6-beta.1", info.tag)
        assertTrue(info.version.isPrerelease)
        assertEquals("> 요약\n- 항목", info.body)
        val apk = info.apk!!
        assertEquals("MrgqPdfViewer-v0.7.6-beta.1-release.apk", apk.name)
        assertEquals("$server/api/v1/app-releases/android/v0.7.6-beta.1/apk/", apk.downloadUrl)
        assertEquals(6676762L, apk.size)
        assertEquals(serverSha, apk.sha256) // 소문자로
        assertNull(info.checksums)
        assertEquals("$server/download/", info.htmlUrl)
    }

    @Test
    fun 서버_응답의_빠진_값과_이상한_값() {
        // 받기 주소 · 파일 이름이 없으면 서버와 버전으로 만든다, 변경 내용이 null 이면 빈 글(→ GitHub CHANGELOG 로)
        val bare = ReleaseInfo.fromScoreMate("""{"version":"v0.7.5","sha256":"$serverSha","notes":null}""", "$server/")
        assertEquals("$server/api/v1/app-releases/android/v0.7.5/apk/", bare.apk!!.downloadUrl)
        assertEquals("MrgqPdfViewer-v0.7.5-release.apk", bare.apk!!.name)
        assertEquals(-1L, bare.apk!!.size)
        assertEquals("", bare.body)
        // 다른 곳(평문 http · 상대 주소)을 가리키는 받기 주소는 쓰지 않는다
        val odd = ReleaseInfo.fromScoreMate("""{"version":"v0.7.5","sha256":"$serverSha","download_url":"http://evil.example/x.apk"}""", server)
        assertEquals("$server/api/v1/app-releases/android/v0.7.5/apk/", odd.apk!!.downloadUrl)
        // 로컬 시험 서버(http)면 http 주소도
        val local = ReleaseInfo.fromScoreMate("""{"version":"v0.7.5","download_url":"http://192.168.0.2:8000/a/apk/"}""", "http://192.168.0.2:8000")
        assertEquals("http://192.168.0.2:8000/a/apk/", local.apk!!.downloadUrl)
        // 해시가 형식에 맞지 않으면 없음 — 검증할 수 없어 설치하지 않는다
        assertNull(ReleaseInfo.fromScoreMate("""{"version":"v0.7.5","sha256":"abc"}""", server).apk!!.sha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 서버_응답에_버전이_없으면_거부() {
        ReleaseInfo.fromScoreMate("""{"apk":"x.apk"}""", server)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 깨진_서버_응답은_거부() {
        ReleaseInfo.fromScoreMate("<html>502</html>", server)
    }
}
