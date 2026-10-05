package com.example.gcodus

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class CharacterSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result =
        if (CharacterDatabase.sync(applicationContext)) Result.success() else Result.retry()
}
