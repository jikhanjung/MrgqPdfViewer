package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.model.PdfFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목록 순서는 표시 문제가 아니라 **정합성 문제**다 — `PdfViewerActivity` 에 인덱스로 파일을
 * 넘기므로, 목록을 두 번 만들었을 때 순서가 다르면 클릭한 것과 다른 파일이 열린다
 * (devlog #030~#032). 여기서 그 계약을 고정한다.
 */
class PdfFileSorterTest {

    private fun file(name: String, modified: Long = 0L) =
        PdfFile(name = name, path = "/pdf/$name", lastModified = modified, size = 1, pageCount = 1)

    private fun names(files: List<PdfFile>) = files.map { it.name }

    // ── 자연 정렬 ────────────────────────────────────────────────────────────

    @Test
    fun `번호가 10 을 넘어도 순서가 유지된다`() {
        // 사전순이면 악보10 < 악보2 가 된다 ('1' < '2'). 악보는 번호로 관리하는 일이 많아
        // 실제로 걸리는 문제였다.
        val input = listOf("악보10.pdf", "악보2.pdf", "악보1.pdf", "악보11.pdf", "악보20.pdf", "악보3.pdf")
            .map { file(it) }
        assertEquals(
            listOf("악보1.pdf", "악보2.pdf", "악보3.pdf", "악보10.pdf", "악보11.pdf", "악보20.pdf"),
            names(PdfFileSorter.sort(input, PdfFileSorter.BY_NAME))
        )
    }

    @Test
    fun `숫자가 여러 군데 있어도 각각 수치로 비교한다`() {
        val input = listOf("2악장 10번.pdf", "2악장 2번.pdf", "10악장 1번.pdf", "1악장 9번.pdf")
            .map { file(it) }
        assertEquals(
            listOf("1악장 9번.pdf", "2악장 2번.pdf", "2악장 10번.pdf", "10악장 1번.pdf"),
            names(PdfFileSorter.sort(input, PdfFileSorter.BY_NAME))
        )
    }

    @Test
    fun `대소문자를 무시한다`() {
        val input = listOf("Zebra.pdf", "apple.pdf", "Mango.pdf", "banana.pdf").map { file(it) }
        assertEquals(
            listOf("apple.pdf", "banana.pdf", "Mango.pdf", "Zebra.pdf"),
            names(PdfFileSorter.sort(input, PdfFileSorter.BY_NAME))
        )
    }

    @Test
    fun `한글은 가나다 순이다`() {
        val input = listOf("사랑.pdf", "가을.pdf", "나비.pdf", "다리.pdf").map { file(it) }
        assertEquals(
            listOf("가을.pdf", "나비.pdf", "다리.pdf", "사랑.pdf"),
            names(PdfFileSorter.sort(input, PdfFileSorter.BY_NAME))
        )
    }

    @Test
    fun `선행 0 이 있어도 수치가 같으면 인접하고 순서는 결정적이다`() {
        val input = listOf("t007.pdf", "t7.pdf", "t07.pdf").map { file(it) }
        val sorted = names(PdfFileSorter.sort(input, PdfFileSorter.BY_NAME))
        assertEquals(3, sorted.size)
        // 어떤 순서로 들어와도 같은 결과여야 한다 (전순서)
        assertEquals(sorted, names(PdfFileSorter.sort(input.reversed(), PdfFileSorter.BY_NAME)))
        assertEquals(sorted, names(PdfFileSorter.sort(input.shuffled(java.util.Random(1)), PdfFileSorter.BY_NAME)))
    }

    @Test
    fun `짧은 이름이 접두사면 먼저 온다`() {
        val input = listOf("Moldau0607.pdf", "Moldau.pdf", "Moldau06.pdf").map { file(it) }
        assertEquals(
            listOf("Moldau.pdf", "Moldau06.pdf", "Moldau0607.pdf"),
            names(PdfFileSorter.sort(input, PdfFileSorter.BY_NAME))
        )
    }

    @Test
    fun `아주 큰 번호도 오버플로 없이 비교된다`() {
        val input = listOf("v99999999999999999999.pdf", "v100000000000000000000.pdf", "v9.pdf")
            .map { file(it) }
        assertEquals(
            listOf("v9.pdf", "v99999999999999999999.pdf", "v100000000000000000000.pdf"),
            names(PdfFileSorter.sort(input, PdfFileSorter.BY_NAME))
        )
    }

    // ── 시간 정렬 ────────────────────────────────────────────────────────────

    @Test
    fun `최신 파일이 먼저 온다`() {
        val input = listOf(file("a.pdf", 100), file("b.pdf", 300), file("c.pdf", 200))
        assertEquals(
            listOf("b.pdf", "c.pdf", "a.pdf"),
            names(PdfFileSorter.sort(input, PdfFileSorter.BY_TIME))
        )
    }

    @Test
    fun `타임스탬프가 같으면 이름으로 갈라 결정적이다`() {
        // 웹으로 한 번에 여러 개를 올리면 mtime 이 같아진다. 기존 구현은 이때
        // File.listFiles() 순서(파일시스템 미지정)를 그대로 따라가 목록을 다시 만들 때마다
        // 순서가 달라질 수 있었다 — #030 계열 인덱스 불일치의 구조적 원인.
        val same = 1_700_000_000L
        val input = listOf(file("악보3.pdf", same), file("악보1.pdf", same), file("악보2.pdf", same))
        val expected = listOf("악보1.pdf", "악보2.pdf", "악보3.pdf")
        assertEquals(expected, names(PdfFileSorter.sort(input, PdfFileSorter.BY_TIME)))
        assertEquals(expected, names(PdfFileSorter.sort(input.reversed(), PdfFileSorter.BY_TIME)))
    }

    // ── 전순서 계약 (인덱스 정합성의 근거) ────────────────────────────────────

    @Test
    fun `입력 순서가 무엇이든 결과가 같다`() {
        val base = listOf(
            file("악보10.pdf", 5), file("Bach.pdf", 5), file("바흐.pdf", 3),
            file("악보2.pdf", 5), file("bach.pdf", 9), file("Moldau.pdf", 1),
        )
        for (mode in listOf(PdfFileSorter.BY_NAME, PdfFileSorter.BY_TIME)) {
            val reference = names(PdfFileSorter.sort(base, mode))
            val rnd = java.util.Random(42)
            repeat(20) {
                assertEquals("정렬이 입력 순서에 좌우된다 ($mode)",
                    reference, names(PdfFileSorter.sort(base.shuffled(rnd), mode)))
            }
        }
    }

    @Test
    fun `동률이 남지 않는다 — 이름이 다르면 항상 순서가 정해진다`() {
        val names = listOf("a.pdf", "A.pdf", "a1.pdf", "a01.pdf", "가.pdf", "1.pdf", "01.pdf")
        for (x in names) for (y in names) {
            if (x == y) continue
            assertTrue("'$x' 와 '$y' 가 동률로 남았다",
                PdfFileSorter.compareNaturally(x, y) != 0)
        }
    }

    @Test
    fun `비교자는 대칭이고 자기 자신과는 같다`() {
        val names = listOf("악보1.pdf", "악보10.pdf", "Bach.pdf", "bach.pdf", "가.pdf")
        for (x in names) {
            assertEquals(0, PdfFileSorter.compareNaturally(x, x))
            for (y in names) {
                val a = PdfFileSorter.compareNaturally(x, y)
                val b = PdfFileSorter.compareNaturally(y, x)
                assertTrue("대칭성 위반: $x vs $y ($a, $b)",
                    (a == 0 && b == 0) || (a > 0 && b < 0) || (a < 0 && b > 0))
            }
        }
    }

    @Test
    fun `빈 목록과 단일 항목을 처리한다`() {
        assertEquals(emptyList<String>(), names(PdfFileSorter.sort(emptyList(), PdfFileSorter.BY_NAME)))
        assertEquals(listOf("a.pdf"), names(PdfFileSorter.sort(listOf(file("a.pdf")), PdfFileSorter.BY_TIME)))
    }

    @Test
    fun `알 수 없는 정렬 모드는 이름순으로 떨어진다`() {
        val input = listOf(file("b.pdf", 1), file("a.pdf", 9))
        assertEquals(listOf("a.pdf", "b.pdf"), names(PdfFileSorter.sort(input, "존재하지-않는-모드")))
    }
}
