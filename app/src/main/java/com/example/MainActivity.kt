package com.example

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.AppDatabase
import com.example.data.repository.TimetableRepository
import com.example.ui.TimetableViewModel
import com.example.ui.TimetableViewModelFactory
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.worker.TimetableGeneratorWorker

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize DB, Repo & ViewModel
        val db = AppDatabase.getDatabase(applicationContext)
        val repository = TimetableRepository(db)
        val viewModelFactory = TimetableViewModelFactory(repository)
        val viewModel = ViewModelProvider(this, viewModelFactory)[TimetableViewModel::class.java]
        viewModel.initPrefs(applicationContext)

        // Auto-fetch if no schedule loaded yet
        if (viewModel.activeFile.value == null) {
            viewModel.syncFromLiveLink(applicationContext, isSilent = true)
        }

        // 2. Schedule Background WorkManager Tasks
        TimetableGeneratorWorker.schedulePeriodicWork(applicationContext)

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                // Request POST_NOTIFICATIONS runtime permission on Android 13+ (API 33)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { _ -> }
                    LaunchedEffect(Unit) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // Setup if we should show Bottom Bar (Show on all screens except Splash)
                val showBottomBar = currentRoute != null && currentRoute != "splash"

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                val items = listOf(
                                    BottomNavItem("home", "Home", Icons.Default.Home),
                                    BottomNavItem("upload", "Upload", Icons.Default.Add),
                                    BottomNavItem("timetable", "Timetable", Icons.Default.DateRange),
                                    BottomNavItem("settings", "Preferences", Icons.Default.Settings)
                                )

                                items.forEach { item ->
                                    val selected = currentRoute == item.route
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            if (currentRoute != item.route) {
                                                navController.navigate(item.route) {
                                                    popUpTo("home") { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        label = { Text(item.label) },
                                        icon = { Icon(item.icon, contentDescription = item.label) }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "splash",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("splash") {
                            SplashScreen(navController)
                        }
                        composable("home") {
                            HomeScreen(viewModel, navController)
                        }
                        composable("upload") {
                            UploadExcelScreen(viewModel)
                        }
                        composable("timetable") {
                            TimetableScreen(viewModel)
                        }
                        composable("settings") {
                            SettingsScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}

data class BottomNavItem(val route: String, val label: String, val icon: ImageVector)
