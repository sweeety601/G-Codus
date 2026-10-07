package com.example.gcodus

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager fallback/background runner.
 * The alarm receiver uses NotificationEngine directly so Android does not
 * have to start WorkManager after the alarm fires.
 */
class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result =
        if (NotificationEngine(applicationContext).run()) Result.success() else Result.retry()
}
