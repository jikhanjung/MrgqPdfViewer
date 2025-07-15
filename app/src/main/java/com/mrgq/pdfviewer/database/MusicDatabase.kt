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
import com.mrgq.pdfviewer.database.dao.UserPreferenceDao
import com.mrgq.pdfviewer.database.entity.PdfFile
import com.mrgq.pdfviewer.database.entity.UserPreference

@Database(
    entities = [PdfFile::class, UserPreference::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {
    
    abstract fun pdfFileDao(): PdfFileDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    
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
        
        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}