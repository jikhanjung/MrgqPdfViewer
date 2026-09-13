package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.score.PathBox
import com.mrgq.pdfviewer.score.PathContentInterpreter
import com.mrgq.pdfviewer.score.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 콘텐츠 스트림 경량 해석기 — PDF 문법 경계 사례.
 * 실제 악보에서 PdfBox 엔진과 같은 박스를 내는지는 계측 테스트 PathInterpreterEquivalenceTest 가 본다.
 */
class PathContentInterpreterTest {

    private fun boxes(
        content: String,
        originX: Float = 0f,
        originY: Float = 0f,
        resolver: PathContentInterpreter.XObjectResolver? = null,
    ): List<PathBox> = boxes(content.toByteArray(Charsets.ISO_8859_1), originX, originY, resolver)

    private fun boxes(
        content: ByteArray,
        originX: Float = 0f,
        originY: Float = 0f,
        resolver: PathContentInterpreter.XObjectResolver? = null,
    ): List<PathBox> {
        val out = ArrayList<PathBox>()
        PathContentInterpreter(originX, originY) { out += it }.run(content, resolver)
        return out
    }

    @Test
    fun 직선_경로의_박스() {
        assertEquals(listOf(PathBox(10f, 20f, 30f, 40f, curved = false)), boxes("10 20 m 30 40 l S"))
    }

    @Test
    fun 곡선은_끝점만_넣고_곡선으로_표시한다() {
        // 제어점(50)은 박스에 들어가지 않는다 — PdfBox 수집기·파이썬 분석과 같다
        assertEquals(listOf(PathBox(0f, 0f, 30f, 0f, curved = true)), boxes("0 0 m 10 50 20 50 30 0 c f"))
        assertEquals(listOf(PathBox(0f, 0f, 10f, 0f, curved = true)), boxes("0 0 m 5 5 10 0 v f"))
        assertEquals(listOf(PathBox(0f, 0f, 10f, 0f, curved = true)), boxes("0 0 m 5 5 10 0 y f"))
    }

    @Test
    fun 사각형은_네_모서리() {
        assertEquals(listOf(PathBox(10f, 10f, 110f, 10.5f, curved = false)), boxes("10 10 100 0.5 re f"))
    }

    @Test
    fun 칠하기_전까지_점이_모인다() {
        assertEquals(listOf(PathBox(0f, 0f, 6f, 6f, curved = false)), boxes("0 0 m 1 1 l 5 5 m 6 6 l S"))
    }

    @Test
    fun 칠_연산자마다_박스를_넘긴다() {
        val ops = listOf("f", "F", "f*", "B", "B*", "b", "b*", "S", "s", "n")
        for (op in ops) {
            assertEquals("연산자 $op", 1, boxes("0 0 m 1 1 l $op").size)
        }
        assertTrue("W 와 h 는 박스를 넘기지 않는다", boxes("0 0 m 1 1 l h W").isEmpty())
    }

    @Test
    fun 클립_뒤_n_도_박스를_남긴다() {
        assertEquals(listOf(PathBox(0f, 0f, 595f, 842f, curved = false)), boxes("0 0 595 842 re W n"))
    }

    @Test
    fun cm_은_q_Q_로_되돌린다() {
        val result = boxes("q 2 0 0 2 100 200 cm 0 0 m 10 10 l S Q 0 0 m 1 1 l S")
        assertEquals(listOf(PathBox(100f, 200f, 120f, 220f, false), PathBox(0f, 0f, 1f, 1f, false)), result)
    }

    @Test
    fun cm_은_새_행렬을_앞에_곱한다() {
        // Microsoft Print to PDF 가 쓰는 형태: 페이지 뒤집기 후 0.75 배율
        val result = boxes("q 1 0 0 -1 0 842 cm 0.75 0 0 0.75 10 0 cm 0 0 m 4 4 l S Q")
        assertEquals(listOf(PathBox(10f, 839f, 13f, 842f, false)), result)
    }

    @Test
    fun 페이지_원점을_뺀다() {
        assertEquals(listOf(PathBox(5f, 3f, 15f, 13f, false)), boxes("10 10 m 20 20 l S", originX = 5f, originY = 7f))
    }

    @Test
    fun 짝이_맞지_않는_Q_는_무시한다() {
        assertEquals(listOf(PathBox(1f, 1f, 2f, 2f, false)), boxes("Q Q 1 1 m 2 2 l S"))
    }

    @Test
    fun 문자열_안의_연산자는_실행하지_않는다() {
        val content = "(0 0 m 10 10 l S) Tj (a\\) 5 5 m (nested) 9 9 l S) Tj <4142> Tj [(a) -120 (b)] TJ 1 2 m 3 4 l S"
        assertEquals(listOf(PathBox(1f, 2f, 3f, 4f, false)), boxes(content))
    }

    @Test
    fun 배열_딕셔너리_안의_숫자는_피연산자가_아니다() {
        // TJ 배열의 숫자나 마킹 콘텐츠 속성이 다음 연산자의 피연산자로 새면 좌표가 틀어진다
        assertEquals(listOf(PathBox(1f, 2f, 3f, 4f, false)), boxes("/Span <</MCID 3 /Rect [7 8 9]>> BDC 1 2 m 3 4 l S EMC"))
        assertEquals(listOf(PathBox(5f, 5f, 6f, 6f, false)), boxes("[1 2 3] 0 d 5 5 m 6 6 l S"))
    }

    @Test
    fun 구분자에_붙은_토큰도_읽는다() {
        assertEquals(listOf(PathBox(1f, 1f, 2f, 2f, false)), boxes("[1 2]0 d/GS1 gs 1 1 m 2 2 l S"))
    }

    @Test
    fun 주석은_건너뛴다() {
        assertEquals(listOf(PathBox(1f, 1f, 2f, 2f, false)), boxes("% 0 0 m 9 9 l S\n1 1 m 2 2 l S"))
    }

    @Test
    fun 피연산자가_모자라면_연산자를_건너뛴다() {
        assertEquals(listOf(PathBox(1f, 1f, 2f, 2f, false)), boxes("5 m 1 1 m 2 2 l S"))
    }

    @Test
    fun 숫자_형식() {
        assertEquals(listOf(PathBox(0.5f, -0.5f, 1f, 2f, false)), boxes(".5 -.5 m +1. 2 l S"))
    }

    @Test
    fun 인라인_이미지_데이터는_건너뛴다() {
        val head = "BI /W 2 /H 2 /BPC 8 /CS /G ID ".toByteArray(Charsets.ISO_8859_1)
        val data = byteArrayOf(0x31, 0x20, 0x31, 0x20, 0x6D, 0x0A, 0x53, 0x66) // "1 1 m\nSf" 처럼 보이는 바이너리
        val tail = " EI 1 1 m 2 2 l S".toByteArray(Charsets.ISO_8859_1)
        assertEquals(listOf(PathBox(1f, 1f, 2f, 2f, false)), boxes(head + data + tail))
    }

    @Test
    fun Form_XObject_는_행렬을_곱해_실행한다() {
        val form = PathContentInterpreter.FormXObject(
            "0 0 m 10 10 l S".toByteArray(), floatArrayOf(2f, 0f, 0f, 2f, 5f, 5f), resolver = null
        )
        val resolver = PathContentInterpreter.XObjectResolver { name -> if (name == "Fm1") form else null }
        val result = boxes("q 1 0 0 1 100 0 cm /Fm1 Do Q /Im0 Do 0 0 m 1 1 l S", resolver = resolver)
        assertEquals(listOf(PathBox(105f, 5f, 125f, 25f, false), PathBox(0f, 0f, 1f, 1f, false)), result)
    }

    @Test
    fun Form_XObject_안의_Q_는_바깥_상태를_꺼내지_못한다() {
        val form = PathContentInterpreter.FormXObject("Q Q Q".toByteArray(), floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), null)
        val resolver = PathContentInterpreter.XObjectResolver { form }
        // Do 뒤에도 바깥 cm(×3)이 살아 있어야 한다
        val result = boxes("q 3 0 0 3 0 0 cm /Fm1 Do 1 1 m 2 2 l S Q", resolver = resolver)
        assertEquals(listOf(PathBox(3f, 3f, 6f, 6f, false)), result)
    }

    @Test
    fun 자기_자신을_부르는_Form_은_깊이_제한에서_멈춘다() {
        lateinit var resolver: PathContentInterpreter.XObjectResolver
        val form by lazy {
            PathContentInterpreter.FormXObject("0 0 m 1 1 l S /Loop Do".toByteArray(), floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), resolver)
        }
        resolver = PathContentInterpreter.XObjectResolver { form }
        val result = boxes("/Loop Do", resolver = resolver)
        assertEquals(8, result.size)
    }

    @Test
    fun 이름의_16진_이스케이프를_푼다() {
        var asked: String? = null
        boxes("/Fm#201 Do", resolver = { name -> asked = name; null })
        assertEquals("Fm 1", asked)
    }

    // --- 텍스트 (박자표 읽기용) ---

    /** 글꼴 코드를 ISO-8859-1 로 해독하는 가짜 리소스 */
    private val latinFonts = object : PathContentInterpreter.XObjectResolver {
        override fun form(name: String): PathContentInterpreter.FormXObject? = null
        override fun decodeText(fontName: String, bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)
    }

    private fun texts(content: String, resolver: PathContentInterpreter.XObjectResolver? = latinFonts): List<TextRun> {
        val out = ArrayList<TextRun>()
        PathContentInterpreter(textSink = { out += it }) { }.run(content.toByteArray(Charsets.ISO_8859_1), resolver)
        return out
    }

    @Test
    fun 텍스트_행렬의_위치와_글꼴_크기() {
        assertEquals(listOf(TextRun("6", 100f, 200f, 12f)), texts("BT /F1 12 Tf 1 0 0 1 100 200 Tm (6) Tj ET"))
    }

    @Test
    fun Td_는_누적하고_TD_는_행간을_정한다() {
        val result = texts("BT /F1 10 Tf 10 20 Td (a) Tj 5 -3 TD (b) Tj T* (c) Tj ET")
        assertEquals(listOf(TextRun("a", 10f, 20f, 10f), TextRun("b", 15f, 17f, 10f), TextRun("c", 15f, 14f, 10f)), result)
    }

    @Test
    fun 작은따옴표는_다음_줄에서_보여준다() {
        val result = texts("BT /F1 10 Tf 12 TL 0 100 Td (a) Tj (b) ' ET")
        assertEquals(listOf(TextRun("a", 0f, 100f, 10f), TextRun("b", 0f, 88f, 10f)), result)
    }

    @Test
    fun TJ_배열의_문자열을_잇는다() {
        assertEquals(listOf("12"), texts("BT /F1 10 Tf 0 0 Td [(1) -250 (2)] TJ ET").map { it.text })
    }

    @Test
    fun 헥사_문자열과_리터럴_이스케이프를_푼다() {
        assertEquals(listOf("68", "1(x)"), texts("BT /F1 10 Tf <3638> Tj (\\061\\(x\\)) Tj ET").map { it.text })
    }

    @Test
    fun 뒤집힌_CTM_에서도_위치와_크기가_맞다() {
        // Microsoft Print to PDF 형태: 페이지를 뒤집고 텍스트 행렬도 뒤집는다
        val result = texts("1 0 0 -1 0 842 cm BT /F1 10 Tf 1 0 0 -1 50 100 Tm (8) Tj ET")
        assertEquals(listOf(TextRun("8", 50f, 742f, 10f)), result)
    }

    @Test
    fun 텍스트를_모아도_경로와_피연산자는_그대로다() {
        val boxes = ArrayList<PathBox>()
        val runs = ArrayList<TextRun>()
        PathContentInterpreter(textSink = { runs += it }) { boxes += it }
            .run("BT /F1 10 Tf (1 2 m) Tj ET 1 1 m 2 2 l S".toByteArray(), latinFonts)
        assertEquals(listOf(PathBox(1f, 1f, 2f, 2f, false)), boxes)
        assertEquals(listOf("1 2 m"), runs.map { it.text })
    }

    @Test
    fun 글꼴을_모르면_텍스트를_넘기지_않는다() {
        assertTrue(texts("BT /F1 10 Tf (6) Tj ET", resolver = { null }).isEmpty())
        assertTrue("Tf 없이 보여 주면 무시", texts("BT (6) Tj ET").isEmpty())
    }
}
