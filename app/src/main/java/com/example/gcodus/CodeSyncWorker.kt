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
            // Promo codes are fetched directly from live public sources.
            val json = PromoCodeSource.fetchJson()
            applicationContext.getSharedPreferences("g_codus", Context.MODE_PRIVATE)
                .edit().putString("codes_feed", json).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
