package com.mrgq.pdfviewer.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_preferences",
    foreignKeys = [ForeignKey(
        entity = PdfFile::class,
        parentColumns = ["id"],
        childColumns = ["pdfFileId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class UserPreference(
    @PrimaryKey val pdfFileId: String,
    val displayMode: DisplayMode,        // SINGLE, DOUBLE, AUTO
    val lastPageNumber: Int = 1,
    val bookmarkedPages: String = "",    // JSON array of page numbers
    val topClippingPercent: Float = 0f,  // 위쪽 클리핑 비율 (0.0 ~ 1.0)
    val bottomClippingPercent: Float = 0f, // 아래쪽 클리핑 비율 (0.0 ~ 1.0)
    val centerPadding: Float = 0f,       // 가운데 여백 비율 (0.0 ~ 0.15)
    val updatedAt: Long = System.currentTimeMillis()
)

enum class DisplayMode {
    SINGLE,    // 항상 한 페이지
    DOUBLE,    // 항상 두 페이지  
    AUTO       // orientation에 따라 자동 결정 (기본값)
}