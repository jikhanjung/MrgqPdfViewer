package com.mrgq.pdfviewer.score

import kotlin.math.abs

/**
 * 박자표 하나.
 *
 * @param systemIndex 페이지 안 시스템 번호 (위→아래, 0부터)
 * @param x 박자표 위치 (PDF pt, 페이지 왼쪽부터) — 어느 마디부터 적용되는지 정한다
 */
data class TimeSignatureMark(val systemIndex: Int, val x: Float, val numerator: Int, val denominator: Int)

/**
 * 악보의 텍스트에서 박자표(예: 6/8)를 찾는다 (#050).
 *
 * Moldau(Sibelius → Microsoft Print to PDF) 에서 확인한 배치를 규칙으로 삼았다 — `pdftotext -bbox` 로 본 1쪽 첫 시스템은
 * 보표 5개 모두에 "6" 과 "8" 이 같은 x(106~112pt)에 보표 높이의 절반쯤 간격으로 위아래로 놓여 있었고, 마디 번호("5")나
 * 운지("0") 같은 다른 숫자는 보표 밖에 혼자 있었다.
 *
 * 판정:
 *  - 1~2자리 숫자 텍스트만 본다
 *  - 한 보표 안에서 **x 가 같은(±3pt) 숫자 두 개가 위아래로** 놓임 — 세로 간격이 보표 높이의 25~80%
 *  - 아래 숫자(분모)는 **2의 거듭제곱**(1~32), 위 숫자(분자)는 1~32
 *  - 같은 박자표가 시스템 **보표의 과반**에서 반복
 *
 * 한계: 숫자로 쓰이지 않은 박자표(C, ¢ 기호)는 못 찾는다. 텍스트가 아닌 경로로 그려진 숫자도 못 찾는다.
 * 박자표가 없는 파일은 [detect] 가 빈 목록을 돌려주고, 메트로놈은 악보 연동 없이 동작한다.
 */
object TimeSignatureDetector {

    private const val X_TOL = 3f
    private const val MIN_SEPARATION = 0.25f
    private const val MAX_SEPARATION = 0.8f
    private val DENOMINATORS = setOf(1, 2, 4, 8, 16, 32)
    private val DIGITS = Regex("^\\d{1,2}$")

    private data class Digit(val value: Int, val x: Float, val yTop: Float)
    private data class Candidate(val x: Float, val numerator: Int, val denominator: Int)

    /**
     * @param runs 이 페이지의 텍스트 (PDF y-up 좌표, 해석기가 넘긴 그대로)
     * @param systems 이 페이지의 시스템 (위→아래 좌표)
     */
    fun detect(runs: List<TextRun>, systems: List<SystemLayout>, pageHeight: Float): List<TimeSignatureMark> {
        val digits = runs.mapNotNull { run ->
            val t = run.text.trim()
            if (DIGITS.matches(t)) Digit(t.toInt(), run.x, pageHeight - run.y) else null
        }
        if (digits.isEmpty()) return emptyList()

        val marks = ArrayList<TimeSignatureMark>()
        for ((systemIndex, system) in systems.withIndex()) {
            val perStaff = system.staffBands.map { (top, bottom) -> candidatesInStaff(digits, top, bottom, system) }
            val needed = maxOf(1, (system.staffBands.size + 1) / 2)
            val accepted = ArrayList<Candidate>()
            for (candidate in perStaff.flatten().sortedBy { it.x }) {
                if (accepted.any { abs(it.x - candidate.x) <= X_TOL }) continue
                val staves = perStaff.count { list ->
                    list.any { abs(it.x - candidate.x) <= X_TOL && it.numerator == candidate.numerator && it.denominator == candidate.denominator }
                }
                if (staves >= needed) accepted += candidate
            }
            accepted.forEach { marks += TimeSignatureMark(systemIndex, it.x, it.numerator, it.denominator) }
        }
        return marks
    }

    private fun candidatesInStaff(digits: List<Digit>, top: Float, bottom: Float, system: SystemLayout): List<Candidate> {
        val height = bottom - top
        if (height <= 0f) return emptyList()
        val near = digits.filter {
            it.yTop >= top - height && it.yTop <= bottom + height &&
                it.x >= system.left - X_TOL && it.x <= system.right + X_TOL
        }
        val out = ArrayList<Candidate>()
        for (upper in near) {
            for (lower in near) {
                if (upper === lower) continue
                val separation = lower.yTop - upper.yTop
                if (abs(upper.x - lower.x) <= X_TOL &&
                    separation >= height * MIN_SEPARATION && separation <= height * MAX_SEPARATION &&
                    upper.value in 1..32 && lower.value in DENOMINATORS
                ) {
                    out += Candidate((upper.x + lower.x) / 2, upper.value, lower.value)
                }
            }
        }
        return out
    }
}
