package com.mrgq.pdfviewer.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mrgq.pdfviewer.database.entity.ScoreStaff

@Dao
interface ScoreStaffDao {

    @Query("SELECT * FROM score_staves WHERE pdfFileId = :pdfFileId ORDER BY pageIndex, systemIndex, staffIndex")
    suspend fun getStaves(pdfFileId: String): List<ScoreStaff>

    @Query("DELETE FROM score_staves WHERE pdfFileId = :pdfFileId")
    suspend fun deleteForFile(pdfFileId: String)

    @Insert
    suspend fun insertAll(staves: List<ScoreStaff>)
}
