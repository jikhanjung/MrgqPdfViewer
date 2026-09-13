package com.mrgq.pdfviewer.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mrgq.pdfviewer.database.converter.Converters
import com.mrgq.pdfviewer.database.dao.PdfFileDao
import com.mrgq.pdfviewer.database.dao.ScoreMeasureDao
import com.mrgq.pdfviewer.database.dao.UserPreferenceDao
import com.mrgq.pdfviewer.database.entity.PdfFile
import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.database.entity.UserPreference

@Database(
    entities = [PdfFile::class, UserPreference::class, ScoreMeasure::class],
    version = 8,
    exportSchema = true   // app/schemas 로 내보낸다 — 마이그레이션 테스트·드리프트 감지의 전제
)
@TypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {
    
    abstract fun pdfFileDao(): PdfFileDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun scoreMeasureDao(): ScoreMeasureDao
    
    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null
        
        // Migration from version 1 to 2 (add clipping and padding columns)
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to user_preferences table
                database.execSQL("ALTER TABLE user_preferences ADD COLUMN topClippingPercent REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE user_preferences ADD COLUMN bottomClippingPercent REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE user_preferences ADD COLUMN centerPadding INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        // Migration from version 2 to 3 (change centerPadding from INTEGER to REAL for percentage)
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create a new table with the new schema including foreign key constraint
                database.execSQL("""
                    CREATE TABLE user_preferences_new (
                        pdfFileId TEXT NOT NULL PRIMARY KEY,
                        displayMode TEXT NOT NULL,
                        lastPageNumber INTEGER NOT NULL DEFAULT 1,
                        bookmarkedPages TEXT NOT NULL DEFAULT '',
                        topClippingPercent REAL NOT NULL DEFAULT 0.0,
                        bottomClippingPercent REAL NOT NULL DEFAULT 0.0,
                        centerPadding REAL NOT NULL DEFAULT 0.0,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(pdfFileId) REFERENCES pdf_files(id) ON DELETE CASCADE
                    )
                """)
                
                // Copy data from old table to new table, converting centerPadding to percentage
                // Assuming average page width of 600px for conversion (this is just for migration)
                database.execSQL("""
                    INSERT INTO user_preferences_new 
                    SELECT pdfFileId, displayMode, lastPageNumber, bookmarkedPages, 
                           topClippingPercent, bottomClippingPercent, 
                           CASE WHEN centerPadding > 0 THEN centerPadding / 600.0 ELSE 0.0 END,
                           updatedAt 
                    FROM user_preferences
                """)
                
                // Drop the old table
                database.execSQL("DROP TABLE user_preferences")
                
                // Rename the new table
                database.execSQL("ALTER TABLE user_preferences_new RENAME TO user_preferences")
            }
        }
        
        // Migration from version 3 to 4 (fix foreign key constraint issue)
        // ⚠️ 테이블을 복사 없이 DROP 후 재생성한다 — v1~v3 에서 올라오면 파일별 표시 설정이
        // 전부 초기화된다. 이미 배포된 마이그레이션이라 그대로 두고, 이 동작은
        // MusicDatabaseMigrationTest 가 고정한다.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Drop and recreate the user_preferences table with proper foreign key
                database.execSQL("DROP TABLE IF EXISTS user_preferences")
                database.execSQL("""
                    CREATE TABLE user_preferences (
                        pdfFileId TEXT NOT NULL PRIMARY KEY,
                        displayMode TEXT NOT NULL,
                        lastPageNumber INTEGER NOT NULL DEFAULT 1,
                        bookmarkedPages TEXT NOT NULL DEFAULT '',
                        topClippingPercent REAL NOT NULL DEFAULT 0.0,
                        bottomClippingPercent REAL NOT NULL DEFAULT 0.0,
                        centerPadding REAL NOT NULL DEFAULT 0.0,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(pdfFileId) REFERENCES pdf_files(id) ON DELETE CASCADE
                    )
                """)
            }
        }

        // Migration from version 4 to 5 (PDF 문서 정보: author, docInfoReadAt)
        // title 은 v1 부터 있던 컬럼을 채우기만 한다. 새 컬럼은 둘 다 nullable 이라 기존 행은 null 이고,
        // docInfoReadAt 이 null 인 행은 다음 목록 로드에서 PdfFileSync 가 문서 정보를 읽어 채운다.
        // 테이블을 재생성하지 않으므로 user_preferences 는 그대로 남는다.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pdf_files ADD COLUMN author TEXT")
                database.execSQL("ALTER TABLE pdf_files ADD COLUMN docInfoReadAt INTEGER")
            }
        }

        // Migration from version 5 to 6 (악보 분석: score_measures 테이블 + pdf_files.scoreAnalyzedAt)
        // 새 테이블과 nullable 컬럼만 더하므로 기존 데이터는 그대로다. 마디는 뷰어에서 처음 필요할 때 분석한다.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pdf_files ADD COLUMN scoreAnalyzedAt INTEGER")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `score_measures` (`pdfFileId` TEXT NOT NULL, " +
                        "`measureNumber` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `systemIndex` INTEGER NOT NULL, " +
                        "`leftPt` REAL NOT NULL, `topPt` REAL NOT NULL, `rightPt` REAL NOT NULL, `bottomPt` REAL NOT NULL, " +
                        "`pageWidthPt` REAL NOT NULL, `pageHeightPt` REAL NOT NULL, " +
                        "PRIMARY KEY(`pdfFileId`, `measureNumber`), FOREIGN KEY(`pdfFileId`) REFERENCES `pdf_files`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
            }
        }

        // Migration from version 6 to 7 (메트로놈: 파일별 템포·박자)
        // nullable 컬럼만 더한다. null = 미설정 → 메트로놈이 기본값(120 BPM, 4박)을 쓴다.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_preferences ADD COLUMN metronomeBpm INTEGER")
                database.execSQL("ALTER TABLE user_preferences ADD COLUMN metronomeBeatsPerBar INTEGER")
            }
        }

        // Migration from version 7 to 8 (스키마 변경 없음 — 문서 정보 다시 읽기)
        // v7 까지는 BOM 없는 UTF-8 로 쓰인 작성자·제목이 깨진 채 저장됐다 (실기기 몰다우.pdf "ì€—ìŸ‹ìŽ—", #049).
        // docInfoReadAt 을 비우면 다음 목록 로드에서 PdfFileSync 가 고친 리더로 한 번씩 다시 읽는다 (update 라 표시 설정 보존).
        // 시각 비교 대신 마이그레이션을 쓴 이유: 기기 시계에 기대지 않고 정확히 한 번만 일어난다.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE pdf_files SET docInfoReadAt = NULL")
            }
        }

        /** 앱과 마이그레이션 테스트가 같은 목록을 쓴다 — 등록 누락을 테스트가 잡도록. */
        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
        )

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_database"
                )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}