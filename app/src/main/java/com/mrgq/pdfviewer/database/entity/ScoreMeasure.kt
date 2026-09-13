package com.mrgq.pdfviewer.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * 악보 분석으로 찾은 마디 하나 (v6). 좌표는 **PDF 포인트, 페이지 좌상단 원점, 위→아래**.
 * 박스의 세로 범위는 마디가 속한 시스템 전체다.
 *
 * 파일이 지워지면 함께 지워진다 (CASCADE). 파일이 바뀌면 `PdfFile.scoreAnalyzedAt` 이 비워지고
 * 다음 조회에서 다시 분석해 통째로 바꾼다 (ScoreLayoutStore).
 */
@Entity(
    tableName = "score_measures",
    primaryKeys = ["pdfFileId", "measureNumber"],
    foreignKeys = [ForeignKey(
        entity = PdfFile::class,
        parentColumns = ["id"],
        childColumns = ["pdfFileId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ScoreMeasure(
    val pdfFileId: String,
    // PDF 에서 찾은 **순서** — 문서 전체에서 1부터 연속. MusicXML 의 <measure number> 와는 다를 수 있다
    // (못갖춘마디 "0", implicit 마디, 반복). MusicXML 을 붙일 때 둘 사이 정렬은 별도로 둔다.
    val measureNumber: Int,
    val pageIndex: Int,       // 0부터
    val systemIndex: Int,     // 페이지 안에서 위→아래, 0부터
    val leftPt: Float,
    val topPt: Float,
    val rightPt: Float,
    val bottomPt: Float,
    val pageWidthPt: Float,   // 페이지 CropBox 크기 — 오버레이 좌표 변환에 쓴다
    val pageHeightPt: Float,
    // 이 마디에 적용되는 박자표 (v9). 악보에서 못 읽었거나 첫 박자표 이전이면 null — 메트로놈 마디 연동이 쓴다
    val timeSigNumerator: Int? = null,
    val timeSigDenominator: Int? = null,
)
