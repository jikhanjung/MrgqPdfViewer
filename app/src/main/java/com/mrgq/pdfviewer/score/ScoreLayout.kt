package com.mrgq.pdfviewer.score

import com.mrgq.pdfviewer.database.entity.ScoreMeasure

/** 한 페이지의 분석 결과. 크기는 PDF 포인트(CropBox). */
data class PageLayout(
    val pageIndex: Int,
    val widthPt: Float,
    val heightPt: Float,
    val systems: List<SystemLayout>,
)

/** 문서 전체의 악보 구조. */
data class ScoreLayout(val pages: List<PageLayout>) {

    val measureCount: Int get() = pages.sumOf { page -> page.systems.sumOf { it.measureCount } }

    /**
     * DB 에 저장할 마디 목록. 번호는 **문서 전체에서 1부터 연속** — 인쇄된 마디 번호와 같다는 것을
     * 파이썬 분석에서 육안 대조로 확인했다(#037). 반복 기호로 인한 번호 차이는 다루지 않는다.
     */
    fun toMeasures(pdfFileId: String): List<ScoreMeasure> {
        val measures = ArrayList<ScoreMeasure>()
        for (page in pages) {
            for ((systemIndex, system) in page.systems.withIndex()) {
                for (k in 0 until system.measureCount) {
                    measures += ScoreMeasure(
                        pdfFileId = pdfFileId,
                        measureNumber = measures.size + 1,
                        pageIndex = page.pageIndex,
                        systemIndex = systemIndex,
                        leftPt = system.barlines[k],
                        topPt = system.top,
                        rightPt = system.barlines[k + 1],
                        bottomPt = system.bottom,
                        pageWidthPt = page.widthPt,
                        pageHeightPt = page.heightPt,
                    )
                }
            }
        }
        return measures
    }
}
