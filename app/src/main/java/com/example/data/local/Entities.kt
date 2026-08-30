package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "uploaded_files")
data class UploadedFile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val filePath: String,
    val uploadTimestamp: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

@Entity(tableName = "timetable_entries")
data class TimetableEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileId: Int,
    val day: String,
    val date: String, // yyyy-MM-dd
    val roomNo: String,
    val div: String,
    val slot1: String?, // 8:30 to 10:00
    val slot2: String?, // 10:15 to 11:45
    val slot3: String?, // 12:00 to 13:30
    val slot4: String?, // 14:15 to 15:45
    val slot5: String?  // 16:00 to 17:30
)

@Entity(tableName = "generated_schedules")
data class GeneratedSchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val weekStartDate: String, // yyyy-MM-dd
    val weekEndDate: String, // yyyy-MM-dd
    val pngPath: String,
    val generatedTimestamp: Long = System.currentTimeMillis()
)
