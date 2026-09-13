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
    val metronomeBpm: Int? = null,       // 메트로놈 템포 (v7). null = 미설정 → 기본값. 나중에 MusicXML 템포로 채울 자리
    val metronomeBeatsPerBar: Int? = null, // 메트로놈 마디당 박 수 = 박자표 분자 (v7). 첫 박 강조
    val metronomeBeatUnit: Int? = null,  // 메트로놈 박 단위 = 박자표 분모 (v10). null = 고른 적 없음 → 악보 박자표로 채운다
    val updatedAt: Long = System.currentTimeMillis()
)

enum class DisplayMode {
    SINGLE,    // 항상 한 페이지
    DOUBLE,    // 항상 두 페이지  
    AUTO       // orientation에 따라 자동 결정 (기본값)
}