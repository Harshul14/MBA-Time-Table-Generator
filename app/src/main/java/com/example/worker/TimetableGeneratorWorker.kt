package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.local.AppDatabase
import com.example.data.local.GeneratedSchedule
import com.example.data.repository.TimetableRepository
import com.example.utils.DateUtils
import com.example.utils.NotificationHelper
import com.example.utils.PngRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class TimetableGeneratorWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(applicationContext)
        val repository = TimetableRepository(db)

        // 1. Attempt to auto-sync latest schedule from SharePoint link
        var activeFile = repository.getActiveFile()
        if (activeFile == null) {
            try {
                activeFile = repository.syncFromRemoteUrl(applicationContext).uploadedFile
                Log.d("TimetableWorker", "Successfully synced from SharePoint in background worker.")
            } catch (e: Exception) {
                Log.e("TimetableWorker", "Background sync from SharePoint failed", e)
            }
        }

        if (activeFile == null) {
            return@withContext Result.success()
        }

        // 2. Determine target week
        val today = LocalDate.now()
        val isSunday = today.dayOfWeek == DayOfWeek.SUNDAY

        val targetWeekStart = if (isSunday) {
            today.plusDays(1) // Next week Monday
        } else {
            DateUtils.getWeekStartDate(today) // Current week Monday
        }

        val targetWeekEnd = targetWeekStart.plusDays(6) // Sunday
        val weekStartStr = DateUtils.formatDateToStandard(targetWeekStart)
        val weekEndStr = DateUtils.formatDateToStandard(targetWeekEnd)

        // 3. Check if already generated
        val existingSchedule = repository.getScheduleForWeek(weekStartStr)
        if (existingSchedule != null) {
            return@withContext Result.success()
        }

        // 4. Query timetable entries for this week (Division A)
        val entries = repository.getEntriesForWeek(activeFile.id, weekStartStr, weekEndStr)
        if (entries.isEmpty()) {
            return@withContext Result.success()
        }

        val selectedDiv = repository.getSelectedDivision(applicationContext)

        // 5. Generate PNG
        val pngPath = try {
            PngRenderer.renderWeeklyTimetable(applicationContext, targetWeekStart, entries, selectedDiv)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.retry()
        }

        // 6. Save schedule to Database
        val newSchedule = GeneratedSchedule(
            weekStartDate = weekStartStr,
            weekEndDate = weekEndStr,
            pngPath = pngPath
        )
        repository.insertGeneratedSchedule(newSchedule)

        // 7. Push notification
        val weekRangeStr = DateUtils.formatWeekRange(targetWeekStart, targetWeekEnd)
        NotificationHelper.showTimetableReadyNotification(applicationContext, weekRangeStr, selectedDiv)

        return@withContext Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "weekly_timetable_generator_work"

        /**
         * Schedules a periodic background sync job (checking every 12 hours)
         */
        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<TimetableGeneratorWorker>(
                12, TimeUnit.HOURS
            ).setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
        }

        /**
         * Triggers an immediate one-time generation (for "Generate Now")
         */
        fun triggerImmediateWork(context: Context) {
            val oneTimeRequest = OneTimeWorkRequestBuilder<TimetableGeneratorWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(oneTimeRequest)
        }
    }
}
