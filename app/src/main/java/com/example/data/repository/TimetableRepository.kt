package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.*
import com.example.data.parser.ExcelParser
import com.example.data.remote.RemoteScheduleFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimetableRepository(private val db: AppDatabase) {

    private val uploadedFileDao = db.uploadedFileDao()
    private val timetableEntryDao = db.timetableEntryDao()
    private val generatedScheduleDao = db.generatedScheduleDao()

    val allUploadedFiles: Flow<List<UploadedFile>> = uploadedFileDao.getAllUploadedFiles()
    val allGeneratedSchedules: Flow<List<GeneratedSchedule>> = generatedScheduleDao.getAllGeneratedSchedules()

    suspend fun getActiveFile(): UploadedFile? = uploadedFileDao.getActiveFile()

    fun getEntriesForFile(fileId: Int): Flow<List<TimetableEntry>> = timetableEntryDao.getEntriesForFile(fileId)

    fun getEntriesForWeekFlow(fileId: Int, startDate: String, endDate: String): Flow<List<TimetableEntry>> =
        timetableEntryDao.getEntriesForWeekFlow(fileId, startDate, endDate)

    suspend fun getEntriesForWeek(fileId: Int, startDate: String, endDate: String): List<TimetableEntry> =
        timetableEntryDao.getEntriesForWeek(fileId, startDate, endDate)

    suspend fun getScheduleForWeek(weekStartDate: String): GeneratedSchedule? =
        generatedScheduleDao.getScheduleForWeek(weekStartDate)

    suspend fun insertGeneratedSchedule(schedule: GeneratedSchedule): Long =
        generatedScheduleDao.insertSchedule(schedule)

    suspend fun deleteFile(fileId: Int) = withContext(Dispatchers.IO) {
        uploadedFileDao.deleteFileById(fileId)
        timetableEntryDao.deleteEntriesForFile(fileId)
    }

    suspend fun deleteSchedule(scheduleId: Int) = withContext(Dispatchers.IO) {
        generatedScheduleDao.deleteScheduleById(scheduleId)
    }

    /**
     * Synchronizes and parses the timetable from a remote SharePoint/OneDrive link.
     */
    suspend fun syncFromRemoteUrl(
        context: Context,
        urlString: String = getSavedLiveUrl(context)
    ): UploadedFile = withContext(Dispatchers.IO) {
        // 1. Download file from remote endpoint
        val downloadedFile = RemoteScheduleFetcher.downloadTimetableFile(context, urlString)

        // 2. Prepare database entry
        val displayName = "MBA Batch 17 Trim I (Live SharePoint)"
        val newUploadedFile = UploadedFile(
            fileName = displayName,
            filePath = downloadedFile.absolutePath,
            isActive = true
        )

        uploadedFileDao.deactivateAllFiles()
        val fileId = uploadedFileDao.insertFile(newUploadedFile).toInt()
        val activeFile = newUploadedFile.copy(id = fileId)

        // 3. Parse entries targeting MBA Batch 17 Trim I, Division A
        val parsedEntries = try {
            ExcelParser.parseExcelFile(downloadedFile, fileId)
        } catch (e: Exception) {
            uploadedFileDao.deleteFileById(fileId)
            downloadedFile.delete()
            throw e
        }

        if (parsedEntries.isEmpty()) {
            uploadedFileDao.deleteFileById(fileId)
            downloadedFile.delete()
            throw IllegalStateException("No valid schedule records found for MBA Batch 17 Trim I (Division A) in the SharePoint document.")
        }

        // 4. Save parsed entries to DB
        timetableEntryDao.insertEntries(parsedEntries)

        // 5. Update sync timestamp in preferences
        val nowFormatted = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        setLastSyncTime(context, nowFormatted)
        setSavedLiveUrl(context, urlString)

        return@withContext activeFile
    }

    /**
     * Imports an Excel file manually uploaded by the user from local storage.
     */
    suspend fun importExcelFile(context: Context, uri: Uri, fileName: String): UploadedFile = withContext(Dispatchers.IO) {
        // Copy Excel file to local internal storage
        val localDir = File(context.filesDir, "excel_sheets")
        if (!localDir.exists()) {
            localDir.mkdirs()
        }
        val localFile = File(localDir, "${System.currentTimeMillis()}_$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(localFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Could not open file input stream")

        // Save file entry in database and make it the active one
        val newUploadedFile = UploadedFile(
            fileName = fileName,
            filePath = localFile.absolutePath,
            isActive = true
        )

        // Deactivate all files and insert this one as active
        uploadedFileDao.deactivateAllFiles()
        val fileId = uploadedFileDao.insertFile(newUploadedFile).toInt()
        val activeFile = newUploadedFile.copy(id = fileId)

        // Parse and import timetable entries
        val parsedEntries = try {
            ExcelParser.parseExcelFile(localFile, fileId)
        } catch (e: Exception) {
            uploadedFileDao.deleteFileById(fileId)
            localFile.delete()
            throw e
        }

        if (parsedEntries.isEmpty()) {
            uploadedFileDao.deleteFileById(fileId)
            localFile.delete()
            throw IllegalStateException("No valid schedule records found for MBA Batch 17 Trim I (Division A) in the uploaded Excel.")
        }

        // Insert into database
        timetableEntryDao.insertEntries(parsedEntries)

        return@withContext activeFile
    }

    // Shared Preferences Helpers
    private val PREFS_NAME = "timetable_sync_prefs"
    private val KEY_LIVE_URL = "key_live_sharepoint_url"
    private val KEY_LAST_SYNC_TIME = "key_last_sync_time"

    fun getSavedLiveUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LIVE_URL, RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL)
            ?: RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL
    }

    fun setSavedLiveUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LIVE_URL, url).apply()
    }

    fun getLastSyncTime(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_SYNC_TIME, null)
    }

    fun setLastSyncTime(context: Context, timeStr: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SYNC_TIME, timeStr).apply()
    }
}
