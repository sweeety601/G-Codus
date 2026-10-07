package com.example.gcodus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.Executors

class NotificationAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val ACTION_CHECK = "com.example.gcodus.NOTIFICATION_CHECK"
        private const val REQUEST_CODE = 7815
        private const val INTERVAL_MINUTES = 15L
        private val executor = Executors.newCachedThreadPool()

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingIntent(context)
            val triggerAt = System.currentTimeMillis() + INTERVAL_MINUTES * 60_000L

            alarmManager.cancel(pending)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pending
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pending
                )
            }
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, NotificationAlarmReceiver::class.java).apply {
                    action = ACTION_CHECK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_CHECK
        ) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        executor.execute {
            try {
                NotificationEngine(appContext).run()
            } finally {
                schedule(appContext)
                pendingResult.finish()
            }
        }
    }
}
