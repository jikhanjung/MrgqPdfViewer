package com.mrgq.pdfviewer.database.converter

import androidx.room.TypeConverter
import com.mrgq.pdfviewer.database.entity.PageOrientation
import com.mrgq.pdfviewer.database.entity.DisplayMode

class Converters {
    
    @TypeConverter
    fun fromPageOrientation(orientation: PageOrientation): String {
        return orientation.name
    }
    
    @TypeConverter
    fun toPageOrientation(orientation: String): PageOrientation {
        return PageOrientation.valueOf(orientation)
    }
    
    @TypeConverter
    fun fromDisplayMode(displayMode: DisplayMode): String {
        return displayMode.name
    }
    
    @TypeConverter
    fun toDisplayMode(displayMode: String): DisplayMode {
        return DisplayMode.valueOf(displayMode)
    }
}