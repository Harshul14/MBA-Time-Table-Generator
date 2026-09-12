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

/**
 * Result returned by sync / import operations, exposing metadata detected during parsing.
 */
data class SyncResult(
    val uploadedFile: UploadedFile,
    val detectedTrimester: String,
    val availableDivisions: List<String>
)

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

    suspend fun getDistinctDivisions(fileId: Int): List<String> =
        timetableEntryDao.getDistinctDivisions(fileId)

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

    // ─────────────────────────────────────────────────────────────
    // Remote sync
    // ─────────────────────────────────────────────────────────────

    /**
     * Downloads and parses the timetable from a remote SharePoint/OneDrive link.
     * Returns [SyncResult] containing the active file, detected trimester and available divisions.
     */
    suspend fun syncFromRemoteUrl(
        context: Context,
        urlString: String = getSavedLiveUrl(context)
    ): SyncResult = withContext(Dispatchers.IO) {
        val selectedDiv = getSelectedDivision(context)

        // 1. Download file
        val downloadedFile = RemoteScheduleFetcher.downloadTimetableFile(context, urlString)

        // 2. Insert a provisional DB entry (name updated after parse below)
        val provisionalFile = UploadedFile(
            fileName = "MBA Batch 17 (Live SharePoint)",
            filePath = downloadedFile.absolutePath,
            isActive = true
        )
        uploadedFileDao.deactivateAllFiles()
        val fileId = uploadedFileDao.insertFile(provisionalFile).toInt()

        // 3. Parse
        val parseResult = try {
            ExcelParser.parseExcelFile(downloadedFile, fileId, selectedDiv)
        } catch (e: Exception) {
            uploadedFileDao.deleteFileById(fileId)
            downloadedFile.delete()
            throw e
        }

        if (parseResult.entries.isEmpty()) {
            uploadedFileDao.deleteFileById(fileId)
            downloadedFile.delete()
            throw IllegalStateException(
                "No valid schedule records found for MBA Batch 17 ${parseResult.detectedTrimester} " +
                    "(Division $selectedDiv) in the SharePoint document."
            )
        }

        // 4. Persist entries and update display name with detected trimester
        timetableEntryDao.insertEntries(parseResult.entries)
        val trimLabel = parseResult.detectedTrimester
        val updatedFile = provisionalFile.copy(
            id = fileId,
            fileName = "MBA Batch 17 $trimLabel (Live SharePoint)"
        )

        // 5. Save prefs
        setDetectedTrimester(context, trimLabel)
        setAvailableDivisions(context, parseResult.availableDivisions)
        val nowFormatted = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        setLastSyncTime(context, nowFormatted)
        setSavedLiveUrl(context, urlString)

        return@withContext SyncResult(updatedFile, trimLabel, parseResult.availableDivisions)
    }

    // ─────────────────────────────────────────────────────────────
    // Local Excel import
    // ─────────────────────────────────────────────────────────────

    /**
     * Imports a manually uploaded Excel file.
     * Returns [SyncResult] containing the active file, detected trimester and available divisions.
     */
    suspend fun importExcelFile(context: Context, uri: Uri, fileName: String): SyncResult =
        withContext(Dispatchers.IO) {
            val selectedDiv = getSelectedDivision(context)

            // Copy to internal storage
            val localDir = File(context.filesDir, "excel_sheets").also { it.mkdirs() }
            val localFile = File(localDir, "${System.currentTimeMillis()}_$fileName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(localFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Could not open file input stream")

            val newUploadedFile = UploadedFile(
                fileName = fileName,
                filePath = localFile.absolutePath,
                isActive = true
            )
            uploadedFileDao.deactivateAllFiles()
            val fileId = uploadedFileDao.insertFile(newUploadedFile).toInt()

            val parseResult = try {
                ExcelParser.parseExcelFile(localFile, fileId, selectedDiv)
            } catch (e: Exception) {
                uploadedFileDao.deleteFileById(fileId)
                localFile.delete()
                throw e
            }

            if (parseResult.entries.isEmpty()) {
                uploadedFileDao.deleteFileById(fileId)
                localFile.delete()
                throw IllegalStateException(
                    "No valid schedule records found for MBA Batch 17 ${parseResult.detectedTrimester} " +
                        "(Division $selectedDiv) in the uploaded Excel."
                )
            }

            timetableEntryDao.insertEntries(parseResult.entries)

            val trimLabel = parseResult.detectedTrimester
            setDetectedTrimester(context, trimLabel)
            setAvailableDivisions(context, parseResult.availableDivisions)

            val activeFile = newUploadedFile.copy(id = fileId)
            return@withContext SyncResult(activeFile, trimLabel, parseResult.availableDivisions)
        }

    // ─────────────────────────────────────────────────────────────
    // Re-parse active file (used when division changes)
    // ─────────────────────────────────────────────────────────────

    /**
     * Re-parses the currently active local file with the new [selectedDivision].
     * Returns null if no active file exists or the file is missing.
     */
    suspend fun reparseActiveFile(context: Context): SyncResult? = withContext(Dispatchers.IO) {
        val active = getActiveFile() ?: return@withContext null
        val file = File(active.filePath)
        if (!file.exists()) return@withContext null

        val selectedDiv = getSelectedDivision(context)
        val parseResult = try {
            ExcelParser.parseExcelFile(file, active.id, selectedDiv)
        } catch (e: Exception) {
            return@withContext null
        }

        if (parseResult.entries.isEmpty()) return@withContext null

        timetableEntryDao.deleteEntriesForFile(active.id)
        timetableEntryDao.insertEntries(parseResult.entries)

        setDetectedTrimester(context, parseResult.detectedTrimester)
        setAvailableDivisions(context, parseResult.availableDivisions)

        return@withContext SyncResult(active, parseResult.detectedTrimester, parseResult.availableDivisions)
    }

    // ─────────────────────────────────────────────────────────────
    // SharedPreferences helpers
    // ─────────────────────────────────────────────────────────────

    private val PREFS_NAME = "timetable_sync_prefs"
    private val KEY_LIVE_URL = "key_live_sharepoint_url"
    private val KEY_LAST_SYNC_TIME = "key_last_sync_time"
    private val KEY_DETECTED_TRIMESTER = "key_detected_trimester"
    private val KEY_SELECTED_DIVISION = "key_selected_division"
    private val KEY_AVAILABLE_DIVISIONS = "key_available_divisions"

    fun getSavedLiveUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LIVE_URL, RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL)
            ?: RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL
    }

    fun setSavedLiveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIVE_URL, url).apply()
    }

    fun getLastSyncTime(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SYNC_TIME, null)

    fun setLastSyncTime(context: Context, timeStr: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_SYNC_TIME, timeStr).apply()
    }

    fun getDetectedTrimester(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DETECTED_TRIMESTER, "Trim (Auto)") ?: "Trim (Auto)"

    fun setDetectedTrimester(context: Context, trimester: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DETECTED_TRIMESTER, trimester).apply()
    }

    fun getSelectedDivision(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_DIVISION, "A") ?: "A"

    fun setSelectedDivision(context: Context, division: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_DIVISION, division).apply()
    }

    fun getAvailableDivisions(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AVAILABLE_DIVISIONS, "A") ?: "A"
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.sorted()
    }

    fun setAvailableDivisions(context: Context, divisions: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AVAILABLE_DIVISIONS, divisions.joinToString(",")).apply()
    }
}
