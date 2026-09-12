package com.example.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.repository.TimetableRepository

object NotificationHelper {

    private const val CHANNEL_ID = "timetable_gen_channel"
    private const val CHANNEL_NAME = "Timetable Notifications"
    private const val CHANNEL_DESC = "Notifications for auto-generated MBA weekly timetables"
    private const val NOTIFICATION_ID = 101

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showTimetableReadyNotification(context: Context, weekRangeStr: String, division: String? = null) {
        val resolvedDivision = if (!division.isNullOrBlank()) {
            division.replace("Division", "", ignoreCase = true).trim()
        } else {
            try {
                val db = AppDatabase.getDatabase(context)
                val repo = TimetableRepository(db)
                repo.getSelectedDivision(context)
            } catch (_: Exception) {
                "A"
            }
        }

        // Create channel first
        createNotificationChannel(context)

        // Setup PendingIntent to open MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("OPEN_TIMETABLE", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MBA Division $resolvedDivision Timetable Ready")
            .setContentText("Your timetable for $weekRangeStr (Division $resolvedDivision) is generated.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Your timetable for $weekRangeStr (Division $resolvedDivision) is generated and ready to view.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                }
                notify(NOTIFICATION_ID, builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
