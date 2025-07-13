package com.mrgq.pdfviewer.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mrgq.pdfviewer.database.converter.Converters
import com.mrgq.pdfviewer.database.dao.PdfFileDao
import com.mrgq.pdfviewer.database.dao.UserPreferenceDao
import com.mrgq.pdfviewer.database.entity.PdfFile
import com.mrgq.pdfviewer.database.entity.UserPreference

@Database(
    entities = [PdfFile::class, UserPreference::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {
    
    abstract fun pdfFileDao(): PdfFileDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    
    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null
        
        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}