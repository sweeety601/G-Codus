package com.example.gcodus

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class GcodusFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private val ALLOWED_EVENTS = setOf(
            "promo_new",
            "phase_update",
            "phase_tomorrow",
            "character_tomorrow",
            "character_available",
            "character_confirmed",
            "character_possible"
        )
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FirebaseDeviceSync.onToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val event = message.data["event"] ?: return
        if (event !in ALLOWED_EVENTS) return

        val title = message.notification?.title ?: message.data["title"] ?: "G-Codus"
        val body = message.notification?.body ?: message.data["body"] ?: return
        NotificationHelper.show(this, title, body)
    }
}