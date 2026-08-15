package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.model.PdfFile

/**
 * 파일 목록 정렬 — **결정적(deterministic)이고 자연스러운 순서**.
 *
 * ## 왜 별도로 뽑았나
 *
 * 목록 순서는 이 앱에서 단순한 표시 문제가 아니다. `PdfViewerActivity` 에 **인덱스**로
 * 파일을 넘기기 때문에, 목록을 두 번 만들었을 때 순서가 다르면 **클릭한 것과 다른 파일이
 * 열린다.** devlog #030~#032 가 그 사고들이다.
 *
 * 기존 구현은 `pdfFiles.sortBy { it.name }` / `sortByDescending { it.lastModified }` 였고
 * 두 가지 결함이 있었다:
 *
 * 1. **사전순이라 10 이상에서 순서가 깨진다.** `악보10` < `악보2` (문자 '1' < '2').
 *    악보는 번호를 붙여 관리하는 일이 많아 실제로 걸린다.
 * 2. **시간 정렬의 동률이 비결정적이다.** 같은 타임스탬프면 Kotlin 의 안정 정렬이
 *    `File.listFiles()` 순서를 유지하는데, 그 순서는 **파일시스템이 정하는 미지정 값**이다.
 *    웹으로 한 번에 여러 개를 올리면 mtime 이 같아지므로, 목록을 다시 만들 때마다 순서가
 *    달라질 수 있다 → 인덱스 불일치. **#030 계열 사고의 구조적 원인이 여기 있다.**
 *
 * 그래서 두 정렬 모두 **전순서(total order)** 가 되게 한다 — 어떤 두 파일도 동률로 남지 않고,
 * 입력 순서가 무엇이든 결과가 같다.
 */
object PdfFileSorter {

    const val BY_NAME = "name"
    const val BY_TIME = "time"

    /**
     * 자연 정렬 비교. 숫자 구간은 **수치로**, 나머지는 대소문자 무시 사전순으로 비교한다.
     *
     * `악보2 < 악보10`, `apple < Banana`, `가 < 나`.
     * 완전히 같아 보이면 마지막에 원문을 그대로 비교해 **동률을 남기지 않는다**.
     */
    fun compareNaturally(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // 숫자 구간 전체를 잘라 수치로 비교 (Int 범위를 넘겨도 안전하게 문자열로)
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(startB, j).trimStart('0').ifEmpty { "0" }
                val cmp = if (numA.length != numB.length) {
                    numA.length - numB.length
                } else {
                    numA.compareTo(numB)
                }
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        if (i < a.length) return 1
        if (j < b.length) return -1
        // 여기까지 같으면 대소문자·선행 0 만 다른 경우다. 원문으로 갈라 전순서를 보장한다.
        return a.compareTo(b)
    }

    /** 이름 자연 정렬. */
    val byName: Comparator<PdfFile> = Comparator { x, y -> compareNaturally(x.name, y.name) }

    /**
     * 최신 파일 우선. **동률은 이름으로 깬다** — 이게 없으면 같은 타임스탬프 파일들의 순서가
     * 파일시스템에 좌우돼 목록을 만들 때마다 달라질 수 있다.
     */
    val byTimeDesc: Comparator<PdfFile> = Comparator { x, y ->
        val t = y.lastModified.compareTo(x.lastModified)
        if (t != 0) t else compareNaturally(x.name, y.name)
    }

    /**
     * @param sortBy [BY_NAME] 또는 [BY_TIME]. 알 수 없는 값이면 이름순으로 떨어진다.
     * @return 입력 순서와 무관하게 항상 같은 결과.
     */
    fun sort(files: List<PdfFile>, sortBy: String): List<PdfFile> = when (sortBy) {
        BY_TIME -> files.sortedWith(byTimeDesc)
        else -> files.sortedWith(byName)
    }
}
