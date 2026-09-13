package com.mrgq.pdfviewer.score

import com.mrgq.pdfviewer.database.entity.ScoreMeasure

/** 한 페이지의 분석 결과. 크기는 PDF 포인트(CropBox). */
data class PageLayout(
    val pageIndex: Int,
    val widthPt: Float,
    val heightPt: Float,
    val systems: List<SystemLayout>,
    val timeSignatures: List<TimeSignatureMark> = emptyList(),
)

/** 문서 전체의 악보 구조. */
data class ScoreLayout(val pages: List<PageLayout>) {

    val measureCount: Int get() = pages.sumOf { page -> page.systems.sumOf { it.measureCount } }

    /**
     * DB 에 저장할 마디 목록. 번호는 **문서 전체에서 1부터 연속** — 인쇄된 마디 번호와 같다는 것을
     * 파이썬 분석에서 육안 대조로 확인했다(#037). 반복 기호로 인한 번호 차이는 다루지 않는다.
     *
     * 박자표는 **놓인 마디부터 다음 박자표 전까지** 적용한다 (박자표의 x 가 마디 오른쪽 경계보다 왼쪽이면 그 마디).
     * 첫 박자표 이전 마디는 박자를 모른다(null).
     */
    fun toMeasures(pdfFileId: String): List<ScoreMeasure> {
        val measures = ArrayList<ScoreMeasure>()
        var numerator: Int? = null
        var denominator: Int? = null
        for (page in pages) {
            for ((systemIndex, system) in page.systems.withIndex()) {
                val marks = page.timeSignatures.filter { it.systemIndex == systemIndex }.sortedBy { it.x }
                var nextMark = 0
                for (k in 0 until system.measureCount) {
                    val right = system.barlines[k + 1]
                    while (nextMark < marks.size && marks[nextMark].x < right) {
                        numerator = marks[nextMark].numerator
                        denominator = marks[nextMark].denominator
                        nextMark++
                    }
                    measures += ScoreMeasure(
                        pdfFileId = pdfFileId,
                        measureNumber = measures.size + 1,
                        pageIndex = page.pageIndex,
                        systemIndex = systemIndex,
                        leftPt = system.barlines[k],
                        topPt = system.top,
                        rightPt = right,
                        bottomPt = system.bottom,
                        pageWidthPt = page.widthPt,
                        pageHeightPt = page.heightPt,
                        timeSigNumerator = numerator,
                        timeSigDenominator = denominator,
                    )
                }
            }
        }
        return measures
    }
}
