package com.mrgq.pdfviewer.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ScoreMate 서버에서 받은 악보 (v14, P05 C2) — 서버 악보 id ↔ 이 TV 의 파일. `pdf_files` 와는 경로로 이어진다
 * (파일별 설정은 `pdf_files` 쪽). [hidden] 이면 TV 에서 지운 악보 — 파일이 없고 다시 받지 않는다.
 */
@Entity(tableName = "server_scores")
data class ServerScore(
    @PrimaryKey val serverId: Long,
    val filePath: String,
    val versionNumber: Int,
    val sha256: String,
    val title: String,
    val composer: String,
    val partName: String,
    val ensembleId: Long?,
    val ensembleName: String?,
    val hidden: Boolean,
    val syncedAt: Long,
    /** 편곡자 (서버 0.9.0, P06 §10 — v18). 없으면 빈 문자열 */
    @ColumnInfo(defaultValue = "") val arranger: String = "",
)
