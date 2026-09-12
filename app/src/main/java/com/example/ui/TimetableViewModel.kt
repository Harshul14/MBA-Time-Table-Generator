package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.GeneratedSchedule
import com.example.data.local.TimetableEntry
import com.example.data.local.UploadedFile
import com.example.data.remote.RemoteScheduleFetcher
import com.example.data.repository.TimetableRepository
import com.example.utils.DateUtils
import com.example.utils.NotificationHelper
import com.example.utils.PngRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class TimetableViewModel(private val repository: TimetableRepository) : ViewModel() {

    // Main States
    private val _activeFile = MutableStateFlow<UploadedFile?>(null)
    val activeFile: StateFlow<UploadedFile?> = _activeFile.asStateFlow()

    val uploadedFiles: StateFlow<List<UploadedFile>> = repository.allUploadedFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val generatedSchedules: StateFlow<List<GeneratedSchedule>> = repository.allGeneratedSchedules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _viewedWeekStartDate = MutableStateFlow<LocalDate>(DateUtils.getTargetWeekStartDate())
    val viewedWeekStartDate: StateFlow<LocalDate> = _viewedWeekStartDate.asStateFlow()

    private val _selectedWeekEntries = MutableStateFlow<List<TimetableEntry>>(emptyList())
    val selectedWeekEntries: StateFlow<List<TimetableEntry>> = _selectedWeekEntries.asStateFlow()

    private val _currentGeneratedSchedule = MutableStateFlow<GeneratedSchedule?>(null)
    val currentGeneratedSchedule: StateFlow<GeneratedSchedule?> = _currentGeneratedSchedule.asStateFlow()

    // Loading & Messaging states
    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _success = MutableStateFlow<String?>(null)
    val success: StateFlow<String?> = _success.asStateFlow()

    private val _liveUrl = MutableStateFlow(RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL)
    val liveUrl: StateFlow<String> = _liveUrl.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<String?>(null)
    val lastSyncTime: StateFlow<String?> = _lastSyncTime.asStateFlow()

    init {
        loadActiveFileAndSchedule()
    }

    fun initPrefs(context: Context) {
        _liveUrl.value = repository.getSavedLiveUrl(context)
        _lastSyncTime.value = repository.getLastSyncTime(context)
    }

    private fun loadActiveFileAndSchedule() {
        viewModelScope.launch {
            val active = repository.getActiveFile()
            _activeFile.value = active
            active?.let {
                loadWeekEntriesAndSchedule(it.id, _viewedWeekStartDate.value)
            }
        }
    }

    fun loadWeekEntriesAndSchedule(fileId: Int, weekStart: LocalDate) {
        viewModelScope.launch {
            val weekEnd = weekStart.plusDays(6)
            val startStr = DateUtils.formatDateToStandard(weekStart)
            val endStr = DateUtils.formatDateToStandard(weekEnd)

            val entries = repository.getEntriesForWeek(fileId, startStr, endStr)
            _selectedWeekEntries.value = entries

            val schedule = repository.getScheduleForWeek(startStr)
            _currentGeneratedSchedule.value = schedule
        }
    }

    fun changeViewedWeek(offsetWeeks: Long) {
        val newWeekStart = _viewedWeekStartDate.value.plusWeeks(offsetWeeks)
        _viewedWeekStartDate.value = newWeekStart
        activeFile.value?.let {
            loadWeekEntriesAndSchedule(it.id, newWeekStart)
        }
    }

    /**
     * Automatically fetches, downloads and parses timetable from the configured SharePoint URL.
     */
    fun syncFromLiveLink(context: Context, customUrl: String? = null, isSilent: Boolean = false) {
        viewModelScope.launch {
            _isSyncing.value = !isSilent
            if (!isSilent) {
                _error.value = null
                _success.value = null
            }

            val urlToUse = customUrl ?: _liveUrl.value

            try {
                val active = repository.syncFromRemoteUrl(context, urlToUse)
                _activeFile.value = active
                _lastSyncTime.value = repository.getLastSyncTime(context)
                if (!isSilent) {
                    _success.value = "Timetable synced directly from SharePoint for MBA Batch 17 Trim II (Division A)!"
                }
                loadWeekEntriesAndSchedule(active.id, _viewedWeekStartDate.value)
            } catch (e: Exception) {
                if (!isSilent) {
                    _error.value = e.message ?: "Failed to sync timetable from SharePoint link."
                }
                android.util.Log.e("TimetableViewModel", "Sync from live link failed (silent=$isSilent): ${e.message}", e)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun updateLiveUrl(context: Context, newUrl: String) {
        val trimmed = newUrl.trim()
        if (trimmed.isNotEmpty()) {
            repository.setSavedLiveUrl(context, trimmed)
            _liveUrl.value = trimmed
            _success.value = "Live SharePoint URL updated."
        }
    }

    fun resetLiveUrlToDefault(context: Context) {
        repository.setSavedLiveUrl(context, RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL)
        _liveUrl.value = RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL
        _success.value = "Reset to official MBA Batch 17 Trim II link."
    }

    fun uploadExcelFile(context: Context, uri: Uri, fileName: String) {
        viewModelScope.launch {
            _isParsing.value = true
            _error.value = null
            _success.value = null
            try {
                val active = repository.importExcelFile(context, uri, fileName)
                _activeFile.value = active
                _success.value = "Excel uploaded and MBA Batch 17 Trim II (Division A) parsed successfully."
                loadWeekEntriesAndSchedule(active.id, _viewedWeekStartDate.value)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to parse uploaded Excel file. Ensure headers are correct."
                e.printStackTrace()
            } finally {
                _isParsing.value = false
            }
        }
    }

    fun deleteFile(fileId: Int) {
        viewModelScope.launch {
            repository.deleteFile(fileId)
            if (_activeFile.value?.id == fileId) {
                _activeFile.value = null
                _selectedWeekEntries.value = emptyList()
                _currentGeneratedSchedule.value = null
            }
            _success.value = "File removed successfully."
        }
    }

    fun generateNow(context: Context) {
        val active = _activeFile.value
        if (active == null) {
            _error.value = "No schedule active yet. Please sync from SharePoint or upload an Excel sheet."
            return
        }

        viewModelScope.launch {
            _isParsing.value = true
            _error.value = null
            _success.value = null

            try {
                val weekStart = _viewedWeekStartDate.value
                val weekEnd = weekStart.plusDays(6)
                val startStr = DateUtils.formatDateToStandard(weekStart)
                val endStr = DateUtils.formatDateToStandard(weekEnd)

                val entries = repository.getEntriesForWeek(active.id, startStr, endStr)
                if (entries.isEmpty()) {
                    _error.value = "No classes/schedule records found for Division A in this week."
                    return@launch
                }

                val pngPath = withContext(Dispatchers.IO) {
                    PngRenderer.renderWeeklyTimetable(context, weekStart, entries)
                }

                val newSchedule = GeneratedSchedule(
                    weekStartDate = startStr,
                    weekEndDate = endStr,
                    pngPath = pngPath
                )
                repository.insertGeneratedSchedule(newSchedule)
                _currentGeneratedSchedule.value = newSchedule

                val weekRangeStr = DateUtils.formatWeekRange(weekStart, weekEnd)
                NotificationHelper.showTimetableReadyNotification(context, weekRangeStr)

                _success.value = "Timetable PNG generated successfully!"
            } catch (e: Exception) {
                _error.value = "Generation failed: ${e.message}"
                e.printStackTrace()
            } finally {
                _isParsing.value = false
            }
        }
    }

    fun shareTimetablePng(context: Context, pngPath: String) {
        try {
            val file = File(pngPath)
            if (!file.exists()) {
                _error.value = "Timetable image does not exist. Please generate it first."
                return
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, contentUri)
                type = "image/png"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Timetable"))
        } catch (e: Exception) {
            _error.value = "Failed to share image: ${e.message}"
            e.printStackTrace()
        }
    }

    fun clearMessages() {
        _error.value = null
        _success.value = null
    }

    fun setError(message: String?) {
        _error.value = message
    }
}

class TimetableViewModelFactory(private val repository: TimetableRepository) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TimetableViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TimetableViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
