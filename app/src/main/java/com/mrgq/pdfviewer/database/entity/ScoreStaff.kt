package com.mrgq.pdfviewer.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * 악보 분석으로 찾은 보표 하나 (v15, P07 파트보 보기). 좌표는 [ScoreMeasure] 와 같이 **PDF 포인트, 페이지 좌상단 원점, 위→아래**.
 * 파트보 보기는 시스템마다 고른 파트의 보표 띠를 잘라 이어 붙인다.
 *
 * [ScoreMeasure] 와 같은 분석에서 함께 쓰고 함께 지운다 (ScoreLayoutStore). 파일이 지워지면 함께 지워진다 (CASCADE).
 */
@Entity(
    tableName = "score_staves",
    primaryKeys = ["pdfFileId", "pageIndex", "systemIndex", "staffIndex"],
    foreignKeys = [ForeignKey(
        entity = PdfFile::class,
        parentColumns = ["id"],
        childColumns = ["pdfFileId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ScoreStaff(
    val pdfFileId: String,
    val pageIndex: Int,       // 0부터
    val systemIndex: Int,     // 페이지 안에서 위→아래, 0부터
    val staffIndex: Int,      // 시스템 안에서 위→아래, 0부터
    val topPt: Float,         // 위 오선
    val bottomPt: Float,      // 아래 오선
    /** 보표 왼쪽에 적힌 이름 (StaffLabelDetector). 못 읽으면 null */
    val label: String? = null,
)
