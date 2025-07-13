package com.mrgq.pdfviewer.database.dao

import androidx.room.*
import com.mrgq.pdfviewer.database.entity.UserPreference
import com.mrgq.pdfviewer.database.entity.DisplayMode
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPreferenceDao {
    
    @Query("SELECT * FROM user_preferences")
    fun getAllUserPreferences(): Flow<List<UserPreference>>
    
    @Query("SELECT * FROM user_preferences WHERE pdfFileId = :pdfFileId")
    suspend fun getUserPreference(pdfFileId: String): UserPreference?
    
    @Query("SELECT * FROM user_preferences WHERE pdfFileId = :pdfFileId")
    fun getUserPreferenceFlow(pdfFileId: String): Flow<UserPreference?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserPreference(userPreference: UserPreference)
    
    @Update
    suspend fun updateUserPreference(userPreference: UserPreference)
    
    @Delete
    suspend fun deleteUserPreference(userPreference: UserPreference)
    
    @Query("DELETE FROM user_preferences WHERE pdfFileId = :pdfFileId")
    suspend fun deleteUserPreferenceById(pdfFileId: String)
    
    @Query("DELETE FROM user_preferences")
    suspend fun deleteAllUserPreferences()
    
    @Query("UPDATE user_preferences SET lastPageNumber = :pageNumber, updatedAt = :updatedAt WHERE pdfFileId = :pdfFileId")
    suspend fun updateLastPageNumber(pdfFileId: String, pageNumber: Int, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE user_preferences SET displayMode = :displayMode, updatedAt = :updatedAt WHERE pdfFileId = :pdfFileId")
    suspend fun updateDisplayMode(pdfFileId: String, displayMode: DisplayMode, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE user_preferences SET bookmarkedPages = :bookmarkedPages, updatedAt = :updatedAt WHERE pdfFileId = :pdfFileId")
    suspend fun updateBookmarkedPages(pdfFileId: String, bookmarkedPages: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("SELECT COUNT(*) FROM user_preferences")
    suspend fun getUserPreferenceCount(): Int
}