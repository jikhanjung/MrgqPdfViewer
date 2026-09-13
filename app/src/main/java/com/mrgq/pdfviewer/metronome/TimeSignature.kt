package com.mrgq.pdfviewer.metronome

import kotlin.math.abs
import kotlin.math.ln

/** 클릭 세기. */
enum class Accent { STRONG, MEDIUM, WEAK }

/**
 * 박자표 (분자/분모). 메트로놈의 박은 **분모 음표 하나**이고 BPM 도 그 음표 기준이다 — 6/8 이면 8분음표 6박.
 *
 * 강세: 첫 박은 강하게. 겹박자(분자 6·9·12)는 3박씩 묶어 각 묶음의 첫 박을 중간 세기로 — 6/8 은 1박 강 · 4박 중간
 * (사용자 결정, #051). 홑박자(2/4, 3/4, 4/4 …)는 첫 박만 강조한다.
 *
 * **점음표 박** (`dotted`, #052): 겹박자를 3박 묶음 하나를 한 박으로 센다 — 6/8 은 점4분음표 2박(강 · 중), BPM 도 점4분음표 기준.
 * 빠른 겹박자는 이렇게 센다. 홑박자에는 영향이 없다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
data class TimeSignature(val numerator: Int, val denominator: Int) {

    /** 겹박자 — 분자가 6 이상의 3의 배수면 3박씩 묶는다 (6/8, 9/8, 12/8, 6/4 …). */
    val isCompound: Boolean get() = numerator >= 6 && numerator % 3 == 0

    /** 마디당 박(클릭) 수. 점음표 박이면 3박 묶음 수. */
    fun beatsPerBar(dotted: Boolean = false): Int = if (dotted && isCompound) numerator / 3 else numerator

    /** 점음표 박이면 첫 박 강 · 나머지 중간 — 분모 음표로 셀 때의 묶음 첫 박과 같은 소리다. */
    fun accentAt(indexInBar: Int, dotted: Boolean = false): Accent = when {
        indexInBar == 0 -> Accent.STRONG
        isCompound && (dotted || indexInBar % 3 == 0) -> Accent.MEDIUM
        else -> Accent.WEAK
    }

    /** 박 단위 음표 이름 — "8분음표", 점음표 박이면 "점4분음표" */
    fun beatNoteName(dotted: Boolean = false): String =
        if (dotted && isCompound) "점${denominator / 2}분음표" else "${denominator}분음표"

    /**
     * 메트로놈이 다루는 범위로 자른다. 분자는 [MetronomeClock.MIN_BEATS]..[MetronomeClock.MAX_BEATS],
     * 분모는 [DENOMINATORS] 중 가장 가까운 값 (악보에서 1 이나 32 를 읽은 경우 등).
     */
    fun coerced(): TimeSignature {
        val n = numerator.coerceIn(MetronomeClock.MIN_BEATS, MetronomeClock.MAX_BEATS)
        val d = when {
            denominator in DENOMINATORS -> denominator
            denominator <= 0 -> DEFAULT.denominator
            else -> DENOMINATORS.minByOrNull { abs(ln(it.toDouble() / denominator)) } ?: DEFAULT.denominator
        }
        return if (n == numerator && d == denominator) this else TimeSignature(n, d)
    }

    override fun toString() = "$numerator/$denominator"

    companion object {
        val DEFAULT = TimeSignature(4, 4)

        /** 고를 수 있는 박 단위 */
        val DENOMINATORS = listOf(2, 4, 8, 16)

        /**
         * 대화상자에 미리 보이는 박자 — 흔한 순서. 곡 수 기준의 믿을 만한 코퍼스 통계는 찾지 못했고, 음악 이론 자료들이
         * 공통으로 드는 순서를 따랐다: 4/4 가 압도적, 그다음 3/4 · 2/4 · 6/8, 이어서 2/2(알라 브레베) · 12/8 · 3/8 · 9/8,
         * 홀수 박자 중 가장 흔한 5/4 (#051).
         */
        val COMMON = listOf(
            TimeSignature(4, 4), TimeSignature(3, 4), TimeSignature(2, 4), TimeSignature(6, 8), TimeSignature(2, 2),
            TimeSignature(12, 8), TimeSignature(3, 8), TimeSignature(9, 8), TimeSignature(5, 4),
        )

        /** 저장값·악보에서 읽은 값으로 만든다. 비어 있으면 4/4 의 해당 자리로 채운다. */
        fun of(numerator: Int?, denominator: Int?): TimeSignature =
            TimeSignature(numerator ?: DEFAULT.numerator, denominator ?: DEFAULT.denominator).coerced()
    }
}
