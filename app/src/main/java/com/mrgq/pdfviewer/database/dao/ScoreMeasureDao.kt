package com.mrgq.pdfviewer.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mrgq.pdfviewer.database.entity.ScoreMeasure

@Dao
interface ScoreMeasureDao {

    @Query("SELECT * FROM score_measures WHERE pdfFileId = :pdfFileId ORDER BY measureNumber")
    suspend fun getMeasures(pdfFileId: String): List<ScoreMeasure>

    /** 박자표를 읽은 마디가 있는 파일 — 메트로놈 악보 연동이 되는 악보 (곡 목록의 별, [com.mrgq.pdfviewer.score.ScoreLevel]) */
    @Query("SELECT DISTINCT pdfFileId FROM score_measures WHERE timeSigNumerator IS NOT NULL")
    suspend fun followableFileIds(): List<String>

    @Query("DELETE FROM score_measures WHERE pdfFileId = :pdfFileId")
    suspend fun deleteForFile(pdfFileId: String)

    @Insert
    suspend fun insertAll(measures: List<ScoreMeasure>)
}
