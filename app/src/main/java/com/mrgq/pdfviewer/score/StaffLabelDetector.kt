package com.mrgq.pdfviewer.score

/**
 * 보표 왼쪽에 적힌 이름(악기 · 연주자)을 읽는다 — 파트보 보기의 파트 이름 (P07 §2.1).
 *
 * 보통 첫 시스템에는 긴 이름("Violin I"), 다음부터는 짧은 이름("Vn. I")이 시스템 왼쪽 끝 앞에 보표 높이 가운데쯤 놓인다.
 * 판정:
 *  - 시스템 왼쪽 끝([SystemLayout.left])보다 왼쪽에서 시작하는 텍스트
 *  - 세로로 보표 띠를 위아래 보표 높이만큼 넓힌 범위 안 — 겹치면 가운데가 가장 가까운 보표
 *  - 숫자만 있는 텍스트는 뺀다 (마디 번호가 시스템 왼쪽 위에 있다)
 *  - 한 보표에 여러 조각이면 위→아래, 왼→오른 순으로 공백으로 잇는다
 *
 * 한계: 이름을 글자가 아닌 경로로 그렸거나 글꼴에 유니코드 대응이 없으면 못 읽는다 — Moldau(Sibelius → Microsoft Print to PDF)가
 * 그렇다. 그때는 null 이고 파트 목록은 "보표 n" 으로 부른다 ([ScoreParts]).
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object StaffLabelDetector {

    private val DIGITS_ONLY = Regex("^[\\d\\s]+$")
    /** 시스템 왼쪽 끝과 겹쳐도 되는 여유 — 이름 끝이 마디선에 닿는 경우 */
    private const val LEFT_TOL = 1f

    /**
     * @param runs 이 페이지의 텍스트 (PDF y-up, 해석기가 넘긴 그대로)
     * @param systems 이 페이지의 시스템 (위→아래 좌표)
     * @return [systems] 에 [SystemLayout.staffLabels] 를 채운 것
     */
    fun attach(runs: List<TextRun>, systems: List<SystemLayout>, pageHeight: Float): List<SystemLayout> =
        systems.map { system -> system.copy(staffLabels = labels(runs, system, pageHeight)) }

    private fun labels(runs: List<TextRun>, system: SystemLayout, pageHeight: Float): List<String?> {
        val bands = system.staffBands
        if (bands.isEmpty()) return emptyList()
        val pieces = Array(bands.size) { ArrayList<Pair<Float, TextRun>>() }
        for (run in runs) {
            val text = run.text.trim()
            if (text.isEmpty() || DIGITS_ONLY.matches(text) || run.x >= system.left - LEFT_TOL) continue
            val y = pageHeight - run.y // 기준선, 위→아래
            // 기준선은 글자 아래쪽 — 글자 가운데는 글꼴 크기의 1/3 위
            val center = y - run.size / 3
            var best = -1
            var bestDistance = Float.MAX_VALUE
            for ((k, band) in bands.withIndex()) {
                val height = band.second - band.first
                if (center < band.first - height || center > band.second + height) continue
                val distance = kotlin.math.abs(center - (band.first + band.second) / 2)
                if (distance < bestDistance) {
                    best = k
                    bestDistance = distance
                }
            }
            if (best >= 0) pieces[best] += y to run
        }
        return pieces.map { list ->
            list.sortedWith(compareBy<Pair<Float, TextRun>>({ Math.round(it.first) }, { it.second.x }))
                .joinToString(" ") { it.second.text.trim() }
                .takeIf { it.isNotBlank() }
        }
    }
}
