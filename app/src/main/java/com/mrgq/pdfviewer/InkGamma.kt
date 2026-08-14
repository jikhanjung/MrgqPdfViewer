package com.mrgq.pdfviewer

import android.graphics.Bitmap
import android.util.Log

/**
 * 다운스케일로 희석된 잉크를 되살리는 톤 커브(감마) 보정.
 *
 * ## 배경 (2026-08-15 데스크톱 측정, `data/staffline_profile.py`)
 *
 * PDFium(= Android PdfRenderer 의 내부 엔진)은 1픽셀보다 얇은 stroke 를 최소 1픽셀 실선으로
 * 스냅한다. 그래서 oversample 배율을 올릴수록 오선의 **상대적 두께가 얇아지고**, 화면 크기로
 * 다운스케일할 때 잉크가 희석되어 회색이 된다. 즉 P2 의 4× oversample 은 두께 균일성
 * (P01 증상)을 잡는 대신 전체적인 대비를 떨어뜨렸다.
 *
 * A4 악보 1페이지, fitScale 1.283 (764×1080), 오선 54개 프로파일 측정:
 *
 * | 변형                     | darkness 평균 | darkness 편차 | 두께 편차 |
 * |--------------------------|--------------|--------------|----------|
 * | PDFium 1×                | 0.957        | 0.160        | 0.91     |
 * | PDFium 4×→다운 (현재)     | 0.652        | 0.135        | 0.85     |
 * | MuPDF 4×→다운            | 0.562        | 0.111        | 0.85     |
 * | **4×→다운 + 감마 2.0**    | **0.844**    | 0.149        | 0.88     |
 * | **4×→다운 + 감마 2.5**    | **0.885**    | 0.150        | 0.91     |
 *
 * 감마 보정은 균일성을 거의 그대로 둔 채 대비만 1× 수준 가까이 회복시킨다.
 * (참고: MuPDF 로 엔진을 교체하면 오히려 더 흐려지므로 P5 는 이 문제의 해법이 아니다.)
 *
 * ## 주의 — 기기에서 값을 튜닝해야 한다
 *
 * 위 측정의 다운스케일은 PIL(면적 평균에 가까움)이고, Android `createScaledBitmap` 은
 * mipmap 없는 2×2 bilinear 라 언더샘플링이 있다. 실측에서 그 근사는 darkness 0.719 로
 * 더 진했으므로, **실기기의 적정 감마는 2.0 보다 낮을 수 있다.** PDF 표시 옵션의
 * "오선 진하기" 슬라이더로 실시간 미리보기를 보며 맞출 것.
 */
object InkGamma {

    private const val TAG = "InkGamma"

    const val MIN = 1.0f          // 1.0 = 보정 없음
    const val MAX = 3.0f
    const val DEFAULT = 1.5f      // 눈에 띄되 과하지 않은 출발점. 기기에서 튜닝 전제.

    const val PREF_KEY = "ink_gamma"

    /** 현재 감마. 렌더 스레드에서 읽으므로 @Volatile. */
    @Volatile
    var gamma: Float = DEFAULT
        set(value) {
            val clamped = value.coerceIn(MIN, MAX)
            if (field != clamped) {
                field = clamped
                Log.d(TAG, "감마 변경: $clamped")
            }
        }

    private var cachedGamma = Float.NaN
    private var lut: IntArray? = null

    /** 감마 LUT (256 엔트리). 값이 바뀔 때만 다시 만든다. */
    @Synchronized
    private fun lutFor(g: Float): IntArray {
        val cached = lut
        if (cached != null && cachedGamma == g) return cached
        val table = IntArray(256) { i ->
            (255.0 * Math.pow(i / 255.0, g.toDouble())).toInt().coerceIn(0, 255)
        }
        lut = table
        cachedGamma = g
        return table
    }

    /**
     * 다운스케일 직후의 표시 크기 비트맵에 감마를 적용한다.
     *
     * 흰 배경(255)과 완전한 검정(0)은 그대로 두고 중간톤만 어두워지므로, 희석된 오선·슬러가
     * 진해진다. 감마가 1.0 이면 아무 일도 하지 않고 원본을 그대로 돌려준다.
     *
     * @return 보정된 비트맵. 입력이 immutable 이면 새 비트맵을 만들고 원본은 recycle 한다.
     */
    fun apply(bitmap: Bitmap, g: Float = gamma): Bitmap {
        if (g <= MIN + 0.001f) return bitmap
        if (bitmap.isRecycled) return bitmap

        val target = if (bitmap.isMutable) {
            bitmap
        } else {
            // createScaledBitmap 이 immutable 을 돌려주는 경로가 있어 방어한다.
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            if (copy == null) {
                Log.w(TAG, "mutable 복사 실패 — 감마 보정 건너뜀")
                return bitmap
            }
            bitmap.recycle()
            copy
        }

        val w = target.width
        val h = target.height
        val table = lutFor(g)
        val pixels = IntArray(w * h)
        target.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p and -0x1000000                       // alpha 보존
            val r = table[(p shr 16) and 0xFF]
            val gr = table[(p shr 8) and 0xFF]
            val b = table[p and 0xFF]
            pixels[i] = a or (r shl 16) or (gr shl 8) or b
        }

        target.setPixels(pixels, 0, w, 0, 0, w, h)
        return target
    }
}
