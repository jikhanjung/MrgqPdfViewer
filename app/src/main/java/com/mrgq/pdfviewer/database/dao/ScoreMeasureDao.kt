package com.mrgq.pdfviewer.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mrgq.pdfviewer.database.entity.ScoreMeasure

@Dao
interface ScoreMeasureDao {

    @Query("SELECT * FROM score_measures WHERE pdfFileId = :pdfFileId ORDER BY measureNumber")
    suspend fun getMeasures(pdfFileId: String): List<ScoreMeasure>

    @Query("DELETE FROM score_measures WHERE pdfFileId = :pdfFileId")
    suspend fun deleteForFile(pdfFileId: String)

    @Insert
    suspend fun insertAll(measures: List<ScoreMeasure>)
}
