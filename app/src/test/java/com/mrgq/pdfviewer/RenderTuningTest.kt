package com.mrgq.pdfviewer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 렌더 선명도 관련 순수 로직 테스트.
 *
 * 이 프로젝트에서 **가장 자주 틀렸던 영역**이 렌더 배율·감마 계산이다
 * (P01 두께 불균일 → P2 dropout → #041 감마 → #042 oversample 제거).
 * 세 번 다 실기기에서 눈으로 보고서야 알았고, 세 번 다 상수 하나의 방향 문제였다.
 * 여기 있는 값들은 devlog #041 / #042 의 측정과 결정을 **코드로 고정한 것**이다.
 *
 * 기기도 Android 런타임도 필요 없다 (JVM 단위 테스트).
 */
class InkGammaDefaultTest {

    @Test
    fun `oversample 이 1배면 감마 보정은 꺼진다`() {
        // #042: 1× 에서는 PDFium 이 device pixel 에 스냅해 오선이 이미 순수 검정(darkness 1.000)이다.
        // 이때 감마를 걸면 중간톤만 어두워져 음표·슬러가 무거워진다.
        assertEquals(InkGamma.MIN, InkGamma.defaultFor(1080, 1.0f), 1e-4f)
        assertEquals(InkGamma.MIN, InkGamma.defaultFor(2160, 1.0f), 1e-4f)
        assertEquals(InkGamma.MIN, InkGamma.defaultFor(720, 1.0f), 1e-4f)
    }

    @Test
    fun `1080p 실측 앵커는 2점0 이다`() {
        // #041: Google TV Streamer + 4K 모니터에서 육안 튜닝으로 확정한 값.
        assertEquals(2.0f, InkGamma.defaultFor(1080, 4.0f), 1e-4f)
        assertEquals(InkGamma.REFERENCE_GAMMA, InkGamma.defaultFor(InkGamma.REFERENCE_HEIGHT, 4.0f), 1e-4f)
    }

    @Test
    fun `4K 중립점에서는 보정이 필요 없다`() {
        // 오선이 1픽셀을 넘기므로(0.5pt × fitScale 2.57 ≈ 1.28px) 스스로 또렷해진다.
        assertEquals(InkGamma.MIN, InkGamma.defaultFor(InkGamma.NEUTRAL_HEIGHT, 4.0f), 1e-4f)
    }

    @Test
    fun `해상도가 낮을수록 보정이 커진다`() {
        assertEquals(2.333f, InkGamma.defaultFor(720, 4.0f), 1e-3f)
        assertEquals(1.667f, InkGamma.defaultFor(1440, 4.0f), 1e-3f)
    }

    @Test
    fun `감마는 해상도에 대해 단조 감소한다`() {
        // 부호를 뒤집는 실수(#041 에서 실제로 gamma 방향을 반대로 구현했다가 잡았다)를 막는다.
        var prev = Float.MAX_VALUE
        for (h in intArrayOf(480, 720, 1080, 1440, 1800, 2160, 2880)) {
            val g = InkGamma.defaultFor(h, 4.0f)
            assertTrue("해상도 $h 에서 감마가 증가했다 ($prev → $g)", g <= prev + 1e-4f)
            prev = g
        }
    }

    @Test
    fun `어떤 입력에도 허용 범위를 벗어나지 않는다`() {
        for (h in intArrayOf(-1, 0, 1, 240, 1080, 2160, 4320, 100000)) {
            for (os in floatArrayOf(1f, 1.5f, 2f, 4f, 100f)) {
                val g = InkGamma.defaultFor(h, os)
                assertTrue("h=$h os=$os → $g 가 범위를 벗어남", g >= InkGamma.MIN && g <= InkGamma.MAX)
            }
        }
    }

    @Test
    fun `비정상 화면 높이는 실측 앵커로 방어한다`() {
        assertEquals(InkGamma.REFERENCE_GAMMA, InkGamma.defaultFor(0, 4.0f), 1e-4f)
        assertEquals(InkGamma.REFERENCE_GAMMA, InkGamma.defaultFor(-100, 4.0f), 1e-4f)
    }
}

/**
 * transient oversample 비트맵의 픽셀 상한 클램프.
 *
 * P2-A 에서 oversample 비트맵을 그대로 ImageView 에 넘겼다가 Canvas MAX_BITMAP_SIZE(~100MB)
 * 초과로 크래시했다. 이 함수는 그 재발을 막는 안전장치다.
 */
class EffectiveOversampleFactorTest {

    private val original = PageCache.oversampleFactor

    @After
    fun restore() {
        PageCache.oversampleFactor = original
    }

    @Test
    fun `기본값은 1배 — 네이티브 렌더`() {
        // #042: 기본 정책이 supersampling 제거임을 고정한다.
        assertEquals(1.0f, PageCache.DEFAULT_OVERSAMPLE_FACTOR, 1e-4f)
    }

    @Test
    fun `1배 요청은 계산 없이 1배`() {
        PageCache.oversampleFactor = 1.0f
        assertEquals(1.0f, PageCache.effectiveOversampleFactor(1528, 2160), 1e-4f)
        assertEquals(1.0f, PageCache.effectiveOversampleFactor(99999, 99999), 1e-4f)
    }

    @Test
    fun `1080p 전체화면에서 4배 요청은 축소되지 않는다`() {
        // 07-26 커밋이 "1080p 동작 불변"이라고 주장한 부분. 1920×1080×4² ≈ 33MP 로 상한(34MP) 이내다.
        PageCache.oversampleFactor = 4.0f
        assertEquals(4.0f, PageCache.effectiveOversampleFactor(1920, 1080), 1e-3f)
    }

    @Test
    fun `4K 페이지에서 4배 요청은 상한에 맞춰 축소된다`() {
        PageCache.oversampleFactor = 4.0f
        // 4K 세로 페이지 1528×2160 ≈ 3.3MP → sqrt(34/3.3) ≈ 3.21
        assertEquals(3.21f, PageCache.effectiveOversampleFactor(1528, 2160), 0.02f)
        // 4K 전체화면 3840×2160 ≈ 8.3MP → sqrt(34/8.3) ≈ 2.02
        assertEquals(2.02f, PageCache.effectiveOversampleFactor(3840, 2160), 0.02f)
    }

    @Test
    fun `축소 후에도 상한을 넘지 않는다`() {
        PageCache.oversampleFactor = 4.0f
        for (w in intArrayOf(764, 1528, 1920, 3840, 7680)) {
            for (h in intArrayOf(1080, 2160, 4320)) {
                val f = PageCache.effectiveOversampleFactor(w, h)
                val pixels = (w * f).toLong() * (h * f).toLong()
                assertTrue(
                    "${w}x$h 에서 factor $f → ${pixels / 1_000_000}MP 로 상한 초과",
                    pixels <= PageCache.MAX_OVERSAMPLE_PIXELS + 1_000_000
                )
            }
        }
    }

    @Test
    fun `절대 1배 미만으로 내려가지 않는다`() {
        PageCache.oversampleFactor = 4.0f
        // 상한을 넘겨도 다운샘플링(1 미만)이 되면 안 된다 — 표시 크기보다 작게 렌더하는 셈이 된다.
        assertTrue(PageCache.effectiveOversampleFactor(20000, 20000) >= 1.0f)
    }

    @Test
    fun `비정상 크기는 1배로 방어한다`() {
        PageCache.oversampleFactor = 4.0f
        assertEquals(1.0f, PageCache.effectiveOversampleFactor(0, 1080), 1e-4f)
        assertEquals(1.0f, PageCache.effectiveOversampleFactor(1920, 0), 1e-4f)
        assertEquals(1.0f, PageCache.effectiveOversampleFactor(-1, -1), 1e-4f)
    }
}
