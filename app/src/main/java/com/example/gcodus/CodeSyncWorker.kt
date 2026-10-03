package com.example.gcodus

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL

class CodeSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        return try {
            val connection = URL(MainActivity.CODE_FEED_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "G-Codus/1.0")
            if (connection.responseCode !in 200..299) return Result.retry()

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            applicationContext.getSharedPreferences("g_codus", Context.MODE_PRIVATE)
                .edit().putString("codes_feed", json).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
