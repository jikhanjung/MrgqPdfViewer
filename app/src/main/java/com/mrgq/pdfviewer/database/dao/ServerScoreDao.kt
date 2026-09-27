package com.mrgq.pdfviewer.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mrgq.pdfviewer.database.entity.ServerScore

@Dao
interface ServerScoreDao {
    @Query("SELECT * FROM server_scores ORDER BY serverId")
    suspend fun getAll(): List<ServerScore>

    @Query("SELECT * FROM server_scores WHERE filePath = :filePath AND hidden = 0 LIMIT 1")
    suspend fun getByPath(filePath: String): ServerScore?

    // 다른 테이블이 이 행을 참조하지 않으므로 REPLACE 로 써도 된다 (pdf_files 와 달리 CASCADE 가 없다)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(score: ServerScore)

    @Query("DELETE FROM server_scores WHERE serverId = :serverId")
    suspend fun delete(serverId: Long)
}
