package com.mrgq.pdfviewer.database.entity

import androidx.room.ColumnInfo
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
    val metronomeDottedBeat: Boolean? = null, // 겹박자를 점음표 박으로 센다 (v11). null = 분모 음표로 (기본)
    // 구간별 빠르기 (v13, #057) — 악보 박자가 바뀌는 둘째 구간부터의 설정 JSON (TempoSections). 첫 구간은 위의 템포 · 점음표 박.
    // null = 모두 기본 (음표 길이 그대로 · 분모 음표로 세기)
    val metronomeSections: String? = null,
    // 파트보 보기 (P07) — 보여 줄 보표들의 **비트 마스크**(보표 k = 1 shl k, 여러 파트, v17). null = 전체 악보.
    // 칸 이름은 v16 의 partStaff(보표 순번 하나) 그대로 — v17 마이그레이션이 순번을 마스크로 바꿨다
    @ColumnInfo(name = "partStaff") val partStavesMask: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class DisplayMode {
    SINGLE,    // 항상 한 페이지
    DOUBLE,    // 항상 두 페이지  
    AUTO       // orientation에 따라 자동 결정 (기본값)
}