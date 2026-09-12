package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadedFileDao {
    @Query("SELECT * FROM uploaded_files ORDER BY uploadTimestamp DESC")
    fun getAllUploadedFiles(): Flow<List<UploadedFile>>

    @Query("SELECT * FROM uploaded_files WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveFile(): UploadedFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: UploadedFile): Long

    @Query("UPDATE uploaded_files SET isActive = 0")
    suspend fun deactivateAllFiles()

    @Transaction
    suspend fun setActiveFile(file: UploadedFile): Long {
        deactivateAllFiles()
        return insertFile(file.copy(isActive = true))
    }

    @Query("DELETE FROM uploaded_files WHERE id = :id")
    suspend fun deleteFileById(id: Int)
}

@Dao
interface TimetableEntryDao {
    @Query("SELECT * FROM timetable_entries WHERE fileId = :fileId ORDER BY date ASC")
    fun getEntriesForFile(fileId: Int): Flow<List<TimetableEntry>>

    @Query("SELECT * FROM timetable_entries WHERE fileId = :fileId AND date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    suspend fun getEntriesForWeek(fileId: Int, startDate: String, endDate: String): List<TimetableEntry>

    @Query("SELECT * FROM timetable_entries WHERE fileId = :fileId AND date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getEntriesForWeekFlow(fileId: Int, startDate: String, endDate: String): Flow<List<TimetableEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<TimetableEntry>)

    @Query("DELETE FROM timetable_entries WHERE fileId = :fileId")
    suspend fun deleteEntriesForFile(fileId: Int)

    @Query("SELECT DISTINCT div FROM timetable_entries WHERE fileId = :fileId ORDER BY div ASC")
    suspend fun getDistinctDivisions(fileId: Int): List<String>
}

@Dao
interface GeneratedScheduleDao {
    @Query("SELECT * FROM generated_schedules ORDER BY weekStartDate DESC")
    fun getAllGeneratedSchedules(): Flow<List<GeneratedSchedule>>

    @Query("SELECT * FROM generated_schedules WHERE weekStartDate = :weekStartDate LIMIT 1")
    suspend fun getScheduleForWeek(weekStartDate: String): GeneratedSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: GeneratedSchedule): Long

    @Query("DELETE FROM generated_schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: Int)
}
