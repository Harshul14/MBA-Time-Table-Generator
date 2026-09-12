package com.example.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.example.data.local.GeneratedSchedule
import com.example.data.local.TimetableEntry
import com.example.data.local.UploadedFile
import com.example.data.remote.RemoteScheduleFetcher
import com.example.ui.TimetableViewModel
import com.example.utils.DateUtils
import com.example.utils.NotificationHelper
import com.example.worker.TimetableGeneratorWorker
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

// ==========================================
// SPLASH SCREEN
// ==========================================
@Composable
fun SplashScreen(navController: NavController) {
    val scale = remember { Animatable(0.3f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(key1 = true) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
        alpha.animateTo(1f, animationSpec = tween(1000))
        delay(1400)
        navController.navigate("home") { popUpTo("splash") { inclusive = true } }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha.value))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.DateRange, contentDescription = "App Logo", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(64.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))
            Text(text = "MBA Weekly", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, letterSpacing = 1.sp, modifier = Modifier.testTag("splash_title"))
            Text(text = "Timetable Generator", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, letterSpacing = 0.5.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "MBA Batch 17 • Your Division",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ==========================================
// HOME SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: TimetableViewModel, navController: NavController) {
    val context = LocalContext.current
    val activeFile by viewModel.activeFile.collectAsState()
    val isParsing by viewModel.isParsing.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isRegenerating by viewModel.isRegenerating.collectAsState()
    val error by viewModel.error.collectAsState()
    val success by viewModel.success.collectAsState()
    val currentSchedule by viewModel.currentGeneratedSchedule.collectAsState()
    val viewedWeekStart by viewModel.viewedWeekStartDate.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val selectedWeekEntries by viewModel.selectedWeekEntries.collectAsState()
    val detectedTrimester by viewModel.detectedTrimester.collectAsState()
    val selectedDivision by viewModel.selectedDivision.collectAsState()

    if (isParsing) LoadingDialog(message = "Generating timetable PNG…")
    if (isSyncing) LoadingDialog(message = "Fetching & parsing MBA Batch 17 $detectedTrimester from SharePoint…")
    if (isRegenerating) LoadingDialog(message = "Re-generating timetable image…")

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(text = "MBA Weekly", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Text(text = "Batch 17 $detectedTrimester (Division $selectedDivision)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.syncFromLiveLink(context) }, modifier = Modifier.testTag("home_sync_icon_button")) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Sync from SharePoint", tint = MaterialTheme.colorScheme.primary)
                    }
                    Box(
                        modifier = Modifier.padding(end = 16.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "17${selectedDivision}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── SharePoint Sync Status Card ─────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (activeFile != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(if (activeFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = if (activeFile != null) Icons.Default.CheckCircle else Icons.Default.Info, contentDescription = "Status", tint = Color.White)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (activeFile != null) "SharePoint Live Sync Active" else "No Schedule Loaded",
                                    fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                    color = if (activeFile != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (activeFile != null) "Target: MBA Batch 17 $detectedTrimester • Div $selectedDivision" else "Tap below to auto-fetch from SharePoint link",
                                    fontSize = 13.sp,
                                    color = if (activeFile != null) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        if (lastSyncTime != null) {
                            Text(text = "Last updated: $lastSyncTime", fontSize = 12.sp, color = if (activeFile != null) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.syncFromLiveLink(context) },
                                modifier = Modifier.weight(1f).testTag("sync_from_sharepoint_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isSyncing) "Syncing…" else "Sync from Link")
                            }
                            OutlinedButton(
                                onClick = { navController.navigate("upload") },
                                modifier = Modifier.weight(1f).testTag("manage_sources_button")
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Link / Upload")
                            }
                        }
                    }
                }
            }

            // ── Quick Actions ───────────────────────────────────────
            if (activeFile != null) {
                item {
                    Text(text = "Timetable Actions", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 8.dp))
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = "Current Target Week:\n${DateUtils.formatWeekRange(viewedWeekStart, viewedWeekStart.plusDays(6))}",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    imageVector = if (currentSchedule != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (currentSchedule != null) Color(0xFF10B981) else Color(0xFFF59E0B)
                                )
                                Text(text = if (currentSchedule != null) "PNG Timetable Saved & Ready" else "PNG not generated yet for this week", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { viewModel.generateNow(context) }, modifier = Modifier.weight(1f).testTag("generate_now_button")) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Generate Now")
                                }
                                Button(onClick = { navController.navigate("timetable") }, modifier = Modifier.weight(1f).testTag("view_timetable_button"), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                                    Icon(Icons.Default.DateRange, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("View Grid")
                                }
                            }
                        }
                    }
                }

                item {
                    Text(text = "Today's Schedule Glance", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 8.dp))
                }

                item {
                    val todayStr = DateUtils.formatDateToStandard(LocalDate.now())
                    val todayEntry = selectedWeekEntries.find { it.date == todayStr }

                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "${DateUtils.getDayOfWeekName(todayStr)} • ${DateUtils.formatToDisplay(todayStr)}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp)) {
                                    Text(text = "Room ${todayEntry?.roomNo ?: "TBA"}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            if (todayEntry == null) {
                                Text(text = "No classes scheduled for today or non-working day.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            } else {
                                val slots = listOf("8:30 - 10:00" to (todayEntry.slot1 ?: "Free Slot"), "10:15 - 11:45" to (todayEntry.slot2 ?: "Free Slot"), "12:00 - 13:30" to (todayEntry.slot3 ?: "Free Slot"), "14:15 - 15:45" to (todayEntry.slot4 ?: "Free Slot"), "16:00 - 17:30" to (todayEntry.slot5 ?: "Free Slot"))
                                for ((time, subject) in slots) {
                                    val isFree = subject.uppercase(Locale.US) == "FREE SLOT"
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(text = time, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(text = subject, fontSize = 12.sp, fontWeight = if (isFree) FontWeight.Normal else FontWeight.Bold, fontStyle = if (isFree) FontStyle.Italic else FontStyle.Normal, color = if (isFree) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (error != null) {
        AlertDialog(onDismissRequest = { viewModel.clearMessages() }, title = { Text("Information / Error") }, text = { Text(error ?: "") }, confirmButton = { Button(onClick = { viewModel.clearMessages() }) { Text("OK") } })
    }
    if (success != null) {
        AlertDialog(onDismissRequest = { viewModel.clearMessages() }, title = { Text("Success") }, text = { Text(success ?: "") }, confirmButton = { Button(onClick = { viewModel.clearMessages() }) { Text("Dismiss") } })
    }
}

// ==========================================
// UPLOAD / MASTER SCHEDULE SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadExcelScreen(viewModel: TimetableViewModel) {
    val context = LocalContext.current
    val isParsing by viewModel.isParsing.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val uploadedFiles by viewModel.uploadedFiles.collectAsState()
    val activeFile by viewModel.activeFile.collectAsState()
    val liveUrl by viewModel.liveUrl.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val error by viewModel.error.collectAsState()
    val success by viewModel.success.collectAsState()
    val detectedTrimester by viewModel.detectedTrimester.collectAsState()
    val selectedDivision by viewModel.selectedDivision.collectAsState()

    var showEditUrlDialog by remember { mutableStateOf(false) }
    var editedUrlText by remember(liveUrl) { mutableStateOf(liveUrl) }

    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileNameFromUri(context, uri) ?: "schedule_master.xlsx"
            val lower = fileName.lowercase(Locale.US)
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                viewModel.uploadExcelFile(context, uri, fileName)
            } else {
                viewModel.setError("Invalid file type. Please select a valid Excel file (.xlsx or .xls).")
            }
        }
    }

    if (isParsing) LoadingDialog(message = "Reading Excel sheet & extracting MBA Batch 17 $detectedTrimester…")
    if (isSyncing) LoadingDialog(message = "Downloading & parsing timetable from SharePoint…")

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Timetable Sources", fontWeight = FontWeight.Bold) },
                actions = {
                    Box(
                        modifier = Modifier.padding(end = 16.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "17${selectedDivision}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 14.sp) }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            // Live SharePoint card
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Cloud Source", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Live SharePoint Sync", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(text = "MBA Batch 17 $detectedTrimester • Division $selectedDivision", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "The app pulls directly from the public SharePoint spreadsheet link without requiring sign-in.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    if (lastSyncTime != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "Last synced: $lastSyncTime", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { viewModel.syncFromLiveLink(context) }, modifier = Modifier.weight(1f).testTag("upload_screen_sync_button"), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Now")
                        }
                        OutlinedButton(onClick = { editedUrlText = liveUrl; showEditUrlDialog = true }, modifier = Modifier.weight(1f).testTag("upload_screen_edit_url_button")) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Edit Link")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Manual upload card
            Card(
                modifier = Modifier.fillMaxWidth().clickable { launcher.launch("*/*") }.testTag("upload_manual_excel_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Upload Manual", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Upload Local Excel (.xlsx)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "Optional offline upload from device storage", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "History of Master Schedules", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(10.dp))

            if (uploadedFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "No schedule sheets synced yet.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uploadedFiles) { file ->
                        val isActive = activeFile?.id == file.id
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = if (file.fileName.contains("SharePoint", ignoreCase = true)) Icons.Default.Share else Icons.Default.Menu, contentDescription = null, tint = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = file.fileName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = "Imported on ${DateUtils.formatToDisplay(DateUtils.formatDateToStandard(LocalDate.ofEpochDay(file.uploadTimestamp / 86400000)))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                                }
                                if (isActive) {
                                    SuggestionChip(onClick = { }, label = { Text("Active") }, colors = SuggestionChipDefaults.suggestionChipColors(labelColor = MaterialTheme.colorScheme.secondary))
                                }
                                IconButton(onClick = { viewModel.deleteFile(file.id) }) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditUrlDialog) {
        AlertDialog(
            onDismissRequest = { showEditUrlDialog = false },
            title = { Text("SharePoint Link Configuration") },
            text = {
                Column {
                    Text(text = "Enter or edit the public SharePoint/OneDrive link:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = editedUrlText, onValueChange = { editedUrlText = it }, modifier = Modifier.fillMaxWidth().testTag("edit_url_text_field"), singleLine = false, maxLines = 4, textStyle = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { editedUrlText = RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL }) { Text("Reset to NMIMS Default Link") }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.updateLiveUrl(context, editedUrlText); showEditUrlDialog = false; viewModel.syncFromLiveLink(context, editedUrlText) }, modifier = Modifier.testTag("save_and_sync_button")) { Text("Save & Sync") }
            },
            dismissButton = { TextButton(onClick = { showEditUrlDialog = false }) { Text("Cancel") } }
        )
    }

    if (error != null) {
        AlertDialog(onDismissRequest = { viewModel.clearMessages() }, title = { Text("Processing Error") }, text = { Text(error ?: "") }, confirmButton = { Button(onClick = { viewModel.clearMessages() }) { Text("OK") } })
    }
    if (success != null) {
        AlertDialog(onDismissRequest = { viewModel.clearMessages() }, title = { Text("Success") }, text = { Text(success ?: "") }, confirmButton = { Button(onClick = { viewModel.clearMessages() }) { Text("Dismiss") } })
    }
}

// ==========================================
// TIMETABLE SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(viewModel: TimetableViewModel) {
    val context = LocalContext.current
    val viewedWeekStart by viewModel.viewedWeekStartDate.collectAsState()
    val entries by viewModel.selectedWeekEntries.collectAsState()
    val currentSchedule by viewModel.currentGeneratedSchedule.collectAsState()
    val isParsing by viewModel.isParsing.collectAsState()
    val isRegenerating by viewModel.isRegenerating.collectAsState()
    val detectedTrimester by viewModel.detectedTrimester.collectAsState()
    val selectedDivision by viewModel.selectedDivision.collectAsState()

    val weekEnd = viewedWeekStart.plusDays(6)
    val weekRangeStr = DateUtils.formatWeekRange(viewedWeekStart, weekEnd)

    // Auto-reload entries whenever the selected division or viewed week changes
    LaunchedEffect(selectedDivision, viewedWeekStart) {
        viewModel.refreshCurrentView()
    }

    if (isParsing || isRegenerating) LoadingDialog(message = if (isRegenerating) "Re-generating timetable image…" else "Generating timetable PNG…")

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Division $selectedDivision Timetable", fontWeight = FontWeight.Bold) },
                actions = {
                    // Explicit reload button — useful after switching division from HomeScreen
                    IconButton(
                        onClick = { viewModel.refreshCurrentView() },
                        modifier = Modifier.testTag("timetable_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload timetable for current division",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Box(
                        modifier = Modifier.padding(end = 16.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "17${selectedDivision}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 14.sp) }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.changeViewedWeek(-1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev Week") }
                Text(text = weekRangeStr, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.changeViewedWeek(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Week") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.generateNow(context) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Re-render PNG")
                }
                if (currentSchedule != null) {
                    Button(onClick = { viewModel.shareTimetablePng(context, currentSchedule!!.pngPath) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Image")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "No schedule records found for MBA Batch 17 $detectedTrimester (Division $selectedDivision) in this week.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.syncFromLiveLink(context) }) { Text("Sync from SharePoint") }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val daysList = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
                    for (i in 0..5) {
                        val currentDayName = daysList[i]
                        val expectedDate = viewedWeekStart.plusDays(i.toLong())
                        val expectedDateStr = DateUtils.formatDateToStandard(expectedDate)
                        val dayEntry = entries.find { it.date == expectedDateStr }
                        item {
                            DayTimetableCard(
                                dayName = currentDayName,
                                dateStr = expectedDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)),
                                entry = dayEntry,
                                selectedDivision = selectedDivision,
                                detectedTrimester = detectedTrimester
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// DAY TIMETABLE CARD  (Feature 4: long-press detail)
// ==========================================
@Composable
fun DayTimetableCard(
    dayName: String,
    dateStr: String,
    entry: TimetableEntry?,
    selectedDivision: String = "A",
    detectedTrimester: String = "Trim (Auto)"
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(text = dayName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                    Text(text = dateStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(text = "Room: ${entry?.roomNo ?: "TBA"}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val slots = listOf(
                Triple("8:30 - 10:00", 1, entry?.slot1 ?: "Free Slot"),
                Triple("10:15 - 11:45", 2, entry?.slot2 ?: "Free Slot"),
                Triple("12:00 - 13:30", 3, entry?.slot3 ?: "Free Slot"),
                Triple("14:15 - 15:45", 4, entry?.slot4 ?: "Free Slot"),
                Triple("16:00 - 17:30", 5, entry?.slot5 ?: "Free Slot")
            )

            for ((time, idx, subject) in slots) {
                SlotRow(
                    time = time,
                    subject = subject,
                    slotIndex = idx,
                    entry = entry,
                    dayName = dayName,
                    dateStr = dateStr,
                    selectedDivision = selectedDivision,
                    detectedTrimester = detectedTrimester
                )
            }
        }
    }
}

/**
 * A single slot row in the day card.
 * Long-pressing any non-free slot shows a detail popup card (Feature 4).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlotRow(
    time: String,
    subject: String,
    slotIndex: Int,
    entry: TimetableEntry?,
    dayName: String,
    dateStr: String,
    selectedDivision: String,
    detectedTrimester: String
) {
    val isFree = subject.uppercase(Locale.US) == "FREE SLOT"
    var showDetail by remember { mutableStateOf(false) }

    val outlineColor = MaterialTheme.colorScheme.outline
    val primaryColor = MaterialTheme.colorScheme.primary
    val barColor = if (isFree) outlineColor.copy(alpha = 0.3f) else primaryColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFree) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .drawBehind {
                drawLine(color = barColor, start = Offset(0f, 0f), end = Offset(0f, size.height), strokeWidth = 8f)
            }
            .combinedClickable(
                onClick = { },
                onLongClick = { if (!isFree) showDetail = true }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = time, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = subject,
                fontSize = 13.sp,
                fontWeight = if (isFree) FontWeight.Normal else FontWeight.Bold,
                fontStyle = if (isFree) FontStyle.Italic else FontStyle.Normal,
                color = if (isFree) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp)
            )
            if (!isFree) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Long-press for details",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    if (showDetail && !isFree && entry != null) {
        SlotDetailDialog(
            subject = subject,
            time = time,
            roomNo = entry.roomNo,
            division = selectedDivision,
            dayName = dayName,
            dateStr = dateStr,
            trimester = detectedTrimester,
            onDismiss = { showDetail = false }
        )
    }
}

// ==========================================
// SLOT DETAIL DIALOG  (Feature 4)
// ==========================================
@Composable
fun SlotDetailDialog(
    subject: String,
    time: String,
    roomNo: String,
    division: String,
    dayName: String,
    dateStr: String,
    trimester: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { it / 3 }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(250)),
            exit = slideOutVertically(targetOffsetY = { it / 3 }, animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    // Header
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Class Details", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    // Details
                    SlotDetailRow(label = "Subject", value = subject)
                    SlotDetailRow(label = "Time", value = time)
                    SlotDetailRow(label = "Room / Venue", value = roomNo.ifBlank { "TBA" })
                    SlotDetailRow(label = "Division", value = "Division $division")
                    SlotDetailRow(label = "Day & Date", value = "$dayName, $dateStr")
                    SlotDetailRow(label = "Trimester", value = "MBA Batch 17 $trimester")

                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Long-press any class tile to view its full details.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotDetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        Text(text = value, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    }
}

// ==========================================
// SETTINGS SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: TimetableViewModel) {
    val context = LocalContext.current
    val liveUrl by viewModel.liveUrl.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val detectedTrimester by viewModel.detectedTrimester.collectAsState()
    val selectedDivision by viewModel.selectedDivision.collectAsState()

    var showEditUrlDialog by remember { mutableStateOf(false) }
    var editedUrlText by remember(liveUrl) { mutableStateOf(liveUrl) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("App Preferences", fontWeight = FontWeight.Bold) },
                actions = {
                    Box(
                        modifier = Modifier.padding(end = 16.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) { Text(text = "17${selectedDivision}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 14.sp) }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            item { Text(text = "Live SharePoint Integration", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(text = "MBA Batch 17 $detectedTrimester Source", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Active Link: $liveUrl", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (lastSyncTime != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "Last successful sync: $lastSyncTime", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { viewModel.syncFromLiveLink(context) }, modifier = Modifier.weight(1f)) { Text("Sync Now") }
                            OutlinedButton(onClick = { editedUrlText = liveUrl; showEditUrlDialog = true }, modifier = Modifier.weight(1f)) { Text("Edit Link") }
                        }
                    }
                }
            }

            item { Text(text = "Automated Background Scheduler", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(text = "WorkManager Auto-Checker", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Periodic sync queries the SharePoint master timetable every 12 hours. On Sunday, it executes the off-screen renderer to generate and cache the next week's Division $selectedDivision timetable PNG and triggers a notification alert.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                TimetableGeneratorWorker.schedulePeriodicWork(context)
                                NotificationHelper.showTimetableReadyNotification(context, "MBA Batch 17 $detectedTrimester Ready")
                            },
                            modifier = Modifier.fillMaxWidth().testTag("register_auto_check_button")
                        ) { Text("Re-Register Auto-Check Work") }
                    }
                }
            }

            item { Text(text = "System Diagnostics", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { NotificationHelper.showTimetableReadyNotification(context, "MBA Batch 17 $detectedTrimester (Demo)") },
                            modifier = Modifier.fillMaxWidth().testTag("trigger_demo_notification_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Trigger Test Notification")
                        }
                        Button(
                            onClick = { TimetableGeneratorWorker.triggerImmediateWork(context) },
                            modifier = Modifier.fillMaxWidth().testTag("trigger_diagnostic_worker_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Trigger Immediate Worker Sync")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "About MBA Timetable Generator", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Created to automatically fetch public NMIMS SharePoint timetables for MBA Batch 17 $detectedTrimester and convert Division $selectedDivision records into mobile-friendly shareable weekly grid PNG files.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "App Version: 1.1.0 • MBA Batch 17 $detectedTrimester", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }

    if (showEditUrlDialog) {
        AlertDialog(
            onDismissRequest = { showEditUrlDialog = false },
            title = { Text("SharePoint Link Configuration") },
            text = {
                Column {
                    Text(text = "Enter or edit the public SharePoint/OneDrive link:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = editedUrlText, onValueChange = { editedUrlText = it }, modifier = Modifier.fillMaxWidth().testTag("settings_edit_url_text_field"), singleLine = false, maxLines = 4, textStyle = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { editedUrlText = RemoteScheduleFetcher.DEFAULT_SHAREPOINT_URL }) { Text("Reset to NMIMS Default Link") }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.updateLiveUrl(context, editedUrlText); showEditUrlDialog = false; viewModel.syncFromLiveLink(context, editedUrlText) }) { Text("Save & Sync") }
            },
            dismissButton = { TextButton(onClick = { showEditUrlDialog = false }) { Text("Cancel") } }
        )
    }
}

// ==========================================
// HELPERS & DIALOGS
// ==========================================
@Composable
fun LoadingDialog(message: String) {
    Dialog(onDismissRequest = { }) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = message, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally { cursor?.close() }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) result = result.substring(cut + 1)
    }
    return result
}
