package com.mrgq.pdfviewer.score

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.database.entity.ScoreStaff

/**
 * 파트보 한 조각 — 원본 쪽 [srcPage] 의 사각형(보표 한 줄)을 가상 쪽 [dstPage] 의 [dstTop] 에 그대로(1:1) 옮긴다.
 * 좌표는 모두 **PDF 포인트, 쪽 좌상단 원점, 위→아래** (ScoreMeasure · ScoreStaff 와 같다). 가로 위치는 원본 그대로다.
 *
 * @param firstMeasure 이 조각의 첫 마디 번호 — 조각 왼쪽 위에 적는다 (총보는 보통 맨 위 보표에만 마디 번호가 있다)
 */
data class PartStrip(
    val srcPage: Int,
    val srcSystem: Int,
    val srcLeft: Float,
    val srcTop: Float,
    val srcRight: Float,
    val srcBottom: Float,
    val dstPage: Int,
    val dstTop: Float,
    val firstMeasure: Int?,
) {
    val height: Float get() = srcBottom - srcTop
    val dstBottom: Float get() = dstTop + height
}

/**
 * 파트보 보기의 배치 (P07 §2.2) — 총보의 시스템마다 고른 파트의 보표 띠를 잘라 **세로 A4(원본 쪽 크기)** 가상 쪽에 위에서부터 쌓는다.
 * 크기는 바꾸지 않는다(1:1) — 원본과 같은 선 굵기 · 글자 크기. 가상 쪽을 PDF 로 만들면(PartPdfBuilder) 뷰어의 한 쪽 / 두 쪽 모드 ·
 * 넘김 애니메이션을 그대로 쓴다 (P07 결정: 세로 A4).
 *
 * 띠의 위아래는 **이웃 보표와의 가운데까지** — 덧줄 · 셈여림 · 가사를 살리고 옆 파트는 들이지 않는다. 시스템 맨 위 · 맨 아래 보표는
 * 반대쪽 이웃과의 간격을 그대로 쓴다. 왼쪽은 시스템 첫 마디선에서 [LEFT_PAD] 더 (괄호 · 음자리표 앞), 오른쪽은 끝 마디선에서 [RIGHT_PAD] 더.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
data class PartLayout(
    val staffIndex: Int,
    val pageWidth: Float,
    val pageHeight: Float,
    val pageCount: Int,
    val strips: List<PartStrip>,
) {
    companion object {
        const val TOP_MARGIN = 36f
        const val BOTTOM_MARGIN = 28f
        const val STRIP_GAP = 4f
        const val LEFT_PAD = 8f
        const val RIGHT_PAD = 4f

        /**
         * @param staves 이 파일의 보표 전부 (ScoreParts 가 [ScoreParts.Result.Parts] 를 낸 것 — 시스템마다 보표 수가 같다)
         * @param measures 이 파일의 마디 전부 (시스템 가로 범위와 첫 마디 번호)
         * @return 고른 보표가 있는 시스템이 없으면 null
         */
        fun build(staves: List<ScoreStaff>, measures: List<ScoreMeasure>, staffIndex: Int): PartLayout? {
            val measuresBySystem = measures.groupBy { it.pageIndex to it.systemIndex }
            val first = measures.firstOrNull() ?: return null
            val pageWidth = first.pageWidthPt
            val pageHeight = first.pageHeightPt

            val strips = ArrayList<PartStrip>()
            var dstPage = 0
            var cursor = TOP_MARGIN
            val systems = staves.groupBy { it.pageIndex to it.systemIndex }.toSortedMap(compareBy({ it.first }, { it.second }))
            for ((key, systemStaves) in systems) {
                val bands = systemStaves.sortedBy { it.staffIndex }
                val k = bands.indexOfFirst { it.staffIndex == staffIndex }
                if (k < 0) continue
                val systemMeasures = measuresBySystem[key].orEmpty()
                if (systemMeasures.isEmpty()) continue
                val band = bands[k]
                val srcPageHeight = systemMeasures.first().pageHeightPt
                val srcPageWidth = systemMeasures.first().pageWidthPt

                val above = if (k > 0) (band.topPt - bands[k - 1].bottomPt) / 2 else null
                val below = if (k < bands.size - 1) (bands[k + 1].topPt - band.bottomPt) / 2 else null
                val staffHeight = band.bottomPt - band.topPt
                val fallback = staffHeight * 1.5f // 보표 하나뿐인 시스템 (ScoreParts 가 막지만 안전하게)
                val top = (band.topPt - (above ?: below ?: fallback)).coerceAtLeast(0f)
                val bottom = (band.bottomPt + (below ?: above ?: fallback)).coerceAtMost(srcPageHeight)
                val left = (systemMeasures.minOf { it.leftPt } - LEFT_PAD).coerceAtLeast(0f)
                val right = (systemMeasures.maxOf { it.rightPt } + RIGHT_PAD).coerceAtMost(srcPageWidth)
                val height = bottom - top

                if (cursor + height > pageHeight - BOTTOM_MARGIN && cursor > TOP_MARGIN) {
                    dstPage++
                    cursor = TOP_MARGIN
                }
                strips += PartStrip(
                    srcPage = key.first,
                    srcSystem = key.second,
                    srcLeft = left,
                    srcTop = top,
                    srcRight = right,
                    srcBottom = bottom,
                    dstPage = dstPage,
                    dstTop = cursor,
                    firstMeasure = systemMeasures.minOf { it.measureNumber },
                )
                cursor += height + STRIP_GAP
            }
            if (strips.isEmpty()) return null
            return PartLayout(staffIndex, pageWidth, pageHeight, dstPage + 1, strips)
        }
    }
}
