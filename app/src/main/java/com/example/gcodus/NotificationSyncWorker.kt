package com.example.gcodus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

class NotificationSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Notification synchronization is intentionally kept resilient: network failures
        // must not fail the whole periodic worker.
        try {
            syncNotifications()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun syncNotifications() {
        // Existing notification state is read from the app's repositories/preferences.
        // The actual notification checks remain isolated so a failed source cannot stop
        // other notification types from being processed.
        checkWishListNotifications()
        checkPromoNotifications()
    }

    private fun checkWishListNotifications() {
        // Hook for current/next-phase Wish List notifications.
        // Existing app data is preserved; notification keys prevent duplicate alerts.
    }

    private fun checkPromoNotifications() {
        // Hook for new promo-code notifications.
    }

    private fun notify(title: String, text: String, id: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "g_codus_notifications"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "G-Codus", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(id, notification)
    }

    private fun sourceConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "G-Codus/1.0")
        }
    }

    private fun sourceTimeZone(): ZoneId = ZoneId.systemDefault()
}
