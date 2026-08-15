package com.mrgq.pdfviewer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PageGeometry` characterization 테스트.
 *
 * 가이드(`code-quality.md` §4)의 "커버되지 않은 코드를 리팩터링하기 **전에** 골든 테스트를
 * 쓴다"에 따라, 세 곳에 복제돼 있던 기존 공식의 **출력을 그대로 고정**한 값들이다.
 * 기대값은 추출 전 코드의 산술을 float32 정밀도로 재현해 뽑았다 — 즉 이 테스트가 통과하면
 * 추출이 동작을 바꾸지 않았다는 뜻이다.
 *
 * A4(595×842pt)를 기준 페이지로 쓴다. `PdfRenderer.Page.getWidth()` 가 포인트 단위 정수를
 * 돌려주므로 실제 값과 같다.
 */
class PageGeometryTest {

    private val originalOversample = PageCache.oversampleFactor

    @After
    fun restore() {
        PageCache.oversampleFactor = originalOversample
    }

    private fun a4(
        screenWidth: Int = 1920,
        screenHeight: Int = 1080,
        top: Float = 0f,
        bottom: Float = 0f,
        padding: Float = 0f,
        twoPage: Boolean = false,
    ) = PageGeometry.compute(595, 842, screenWidth, screenHeight, top, bottom, padding, twoPage)

    // ── 골든 값 (추출 전 동작) ────────────────────────────────────────────────

    @Test
    fun `A4 단일 페이지 1080p`() {
        PageCache.oversampleFactor = 1.0f
        val g = a4()
        assertEquals(1.282660f, g.fitScale, 1e-5f)
        assertEquals(763, g.displayWidth)
        assertEquals(1080, g.displayHeight)
        assertEquals(763, g.renderWidth)
        assertEquals(1080, g.renderHeight)
        assertEquals(0f, g.clipTranslateY, 1e-4f)
        assertTrue("1× 면 다운스케일이 no-op 이어야 한다", g.isNativeScale)
    }

    @Test
    fun `A4 두 페이지 1080p — 세로가 제한이라 여백이 크기를 바꾸지 않는다`() {
        PageCache.oversampleFactor = 1.0f
        val noPad = a4(twoPage = true)
        val withPad = a4(twoPage = true, padding = 0.10f)
        // 화면 절반(960)이 아니라 높이(1080/842)가 binding constraint 다.
        assertEquals(1.282660f, noPad.fitScale, 1e-5f)
        assertEquals(763, noPad.displayWidth)
        assertEquals(1080, noPad.displayHeight)
        // 여백 10% 를 줘도 절반 폭이 여전히 넉넉해 페이지 크기는 그대로다.
        assertEquals(noPad.fitScale, withPad.fitScale, 1e-6f)
        assertEquals(noPad.displayWidth, withPad.displayWidth)
    }

    @Test
    fun `클리핑은 vector 변환으로 흡수된다`() {
        PageCache.oversampleFactor = 1.0f
        val g = a4(top = 0.05f, bottom = 0.05f)
        // 보이는 높이가 90% 로 줄면 같은 화면 높이에 더 크게 앉는다.
        assertEquals(1.425178f, g.fitScale, 1e-5f)
        assertEquals(847, g.displayWidth)
        assertEquals(1080, g.displayHeight)
        // 위 5% 를 렌더 좌표계에서 밀어낸다: -842 × 0.05 × 1.425178
        assertEquals(-60.0f, g.clipTranslateY, 0.01f)
    }

    @Test
    fun `아래쪽 클리핑만으로는 이동이 없다`() {
        PageCache.oversampleFactor = 1.0f
        val g = a4(bottom = 0.10f)
        assertEquals(0f, g.clipTranslateY, 1e-4f)
        assertTrue("아래를 자르면 보이는 높이가 줄어 배율이 커진다", g.fitScale > a4().fitScale)
    }

    @Test
    fun `가로 PDF 는 폭이 제한이 된다`() {
        PageCache.oversampleFactor = 1.0f
        val g = PageGeometry.compute(842, 595, 1920, 1080)
        assertEquals(1.815126f, g.fitScale, 1e-5f)
        assertEquals(1528, g.displayWidth)
        assertEquals(1080, g.displayHeight)
    }

    // ── oversample 과의 결합 ─────────────────────────────────────────────────

    @Test
    fun `oversample 4배는 표시 크기를 바꾸지 않고 렌더 크기만 키운다`() {
        PageCache.oversampleFactor = 4.0f
        val g = a4()
        assertEquals(763, g.displayWidth)
        assertEquals(1080, g.displayHeight)
        assertEquals(5.130641f, g.renderScale, 1e-4f)
        assertEquals(3052, g.renderWidth)
        assertEquals(4320, g.renderHeight)
        assertTrue("4× 면 다운스케일 단계가 필요하다", !g.isNativeScale)
    }

    @Test
    fun `4K 두 페이지에서 oversample 은 상한에 맞춰 축소된다`() {
        PageCache.oversampleFactor = 4.0f
        val g = PageGeometry.compute(595, 842, 3840, 2160, twoPageMode = true)
        assertEquals(2.565321f, g.fitScale, 1e-5f)
        assertEquals(1526, g.displayWidth)
        assertEquals(2160, g.displayHeight)
        // 4× 를 그대로 쓰면 페이지당 ~200MB. 34MP 상한이 걸려 실효 배율이 낮아진다.
        assertTrue("상한이 걸려야 한다", g.renderScale < g.fitScale * 4f)
        val pixels = g.renderWidth.toLong() * g.renderHeight
        assertTrue("렌더 픽셀 ${pixels / 1_000_000}MP 가 상한을 크게 넘었다",
            pixels <= PageCache.MAX_OVERSAMPLE_PIXELS + 1_000_000)
    }

    // ── 방어 ────────────────────────────────────────────────────────────────

    @Test
    fun `클리핑 합이 100퍼센트를 넘어도 최소 높이가 남는다`() {
        PageCache.oversampleFactor = 1.0f
        val g = a4(top = 0.6f, bottom = 0.6f)   // 각각 0.45 로 클램프 → 남는 높이 10%
        assertEquals(3.226891f, g.fitScale, 1e-5f)
        assertEquals(1920, g.displayWidth)
        assertEquals(271, g.displayHeight)
        assertTrue(g.displayHeight >= 1)
    }

    @Test
    fun `표시 크기는 절대 0 이 되지 않는다`() {
        PageCache.oversampleFactor = 1.0f
        val g = PageGeometry.compute(595, 842, 1, 1)
        assertTrue(g.displayWidth >= 1)
        assertTrue(g.displayHeight >= 1)
        assertTrue(g.renderWidth >= 1)
        assertTrue(g.renderHeight >= 1)
    }

    @Test
    fun `중앙 여백이 상한을 넘어도 폭이 남는다`() {
        PageCache.oversampleFactor = 1.0f
        val g = PageGeometry.compute(595, 842, 1920, 1080, centerPadding = 5f, twoPageMode = true)
        assertTrue(g.displayWidth >= 1)
        assertTrue(g.fitScale > 0f)
    }

    // ── 단일 출처라는 계약 ───────────────────────────────────────────────────

    @Test
    fun `두 페이지 모드는 단일 모드와 다른 결과를 낼 수 있다`() {
        PageCache.oversampleFactor = 1.0f
        // 폭이 binding 이 되는 조합(가로 PDF)에서는 두 모드가 갈라져야 한다.
        val single = PageGeometry.compute(842, 595, 1920, 1080)
        val two = PageGeometry.compute(842, 595, 1920, 1080, twoPageMode = true)
        assertNotEquals(single.displayWidth, two.displayWidth)
        assertTrue("두 페이지는 절반 폭에 맞춰야 한다", two.displayWidth < single.displayWidth)
    }
}

/**
 * 두 페이지 결합 좌표.
 *
 * #042 에서 **소수점 좌표 blit 이 재샘플링을 일으켜** device-pixel 스냅을 무효화하는 것을
 * 찾았다. 그 계약(정수 좌표)을 여기서 고정한다.
 */
class TwoPageOffsetsTest {

    @Test
    fun `여백 0 일 때 좌우가 각 영역 중앙에 놓인다`() {
        val o = TwoPageOffsets.compute(
            canvasWidth = 1920, canvasHeight = 1080, centerPadding = 0f,
            leftWidth = 763, leftHeight = 1080, rightWidth = 763, rightHeight = 1080
        )
        assertEquals((960 - 763) / 2, o.leftX)
        assertEquals(0, o.leftY)
        assertEquals(960 + (960 - 763) / 2, o.rightX)
        assertEquals(0, o.rightY)
    }

    @Test
    fun `중앙 여백이 좌우를 바깥으로 민다`() {
        val noPad = TwoPageOffsets.compute(1920, 1080, 0f, 700, 1080, 700, 1080)
        val padded = TwoPageOffsets.compute(1920, 1080, 0.10f, 700, 1080, 700, 1080)
        assertTrue("왼쪽 페이지는 더 왼쪽으로", padded.leftX < noPad.leftX)
        assertTrue("오른쪽 페이지는 더 오른쪽으로", padded.rightX > noPad.rightX)
    }

    @Test
    fun `여백이 홀수 픽셀이어도 좌표는 정수다`() {
        // #042: (areaWidth - bitmapWidth) / 2 가 .5 로 떨어지면 Canvas 가 재샘플링해
        // 오선이 뭉개졌다. 정수 나눗셈으로 그 경로를 없앴다.
        for (pad in floatArrayOf(0f, 0.01f, 0.033f, 0.07f, 0.1f, 0.15f)) {
            for (w in intArrayOf(700, 701, 763, 764)) {
                val o = TwoPageOffsets.compute(1921, 1081, pad, w, 1080, w, 1080)
                // Int 타입이므로 소수점이 존재할 수 없다 — 계약이 타입으로 강제됨을 확인한다.
                assertEquals(o.leftX.toFloat(), o.leftX.toInt().toFloat(), 0f)
                assertEquals(o.rightX.toFloat(), o.rightX.toInt().toFloat(), 0f)
            }
        }
    }

    @Test
    fun `높이가 화면보다 작으면 세로 가운데 정렬`() {
        val o = TwoPageOffsets.compute(1920, 1080, 0f, 763, 900, 763, 900)
        assertEquals(90, o.leftY)
        assertEquals(90, o.rightY)
    }

    @Test
    fun `오른쪽 페이지가 없어도 왼쪽 배치는 동일하다`() {
        // 마지막 홀수 페이지 — 왼쪽에만 그린다.
        val both = TwoPageOffsets.compute(1920, 1080, 0.05f, 763, 1080, 763, 1080)
        val leftOnly = TwoPageOffsets.compute(1920, 1080, 0.05f, 763, 1080)
        assertEquals(both.leftX, leftOnly.leftX)
        assertEquals(both.leftY, leftOnly.leftY)
    }
}
