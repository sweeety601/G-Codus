package com.example.gcodus

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

object FirebaseTopicSync {
    private const val PREFS = "g_codus_firebase"
    private const val SUBSCRIPTIONS = "subscriptions"

    private val games = listOf("genshin", "wuwa", "zzz", "starrail", "endfield")

    fun sync(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val desired = linkedSetOf<String>()

        val appPrefs = app.getSharedPreferences("g_codus", Context.MODE_PRIVATE)

        for (game in games) {
            if (appPrefs.getBoolean("favorite_$game", false)) {
                desired += gameTopic(game)
            }
        }

        for (entry in appPrefs.all.entries) {
            val key = entry.key
            if (!key.startsWith("firebase_track_") || entry.value != true) continue
            val rest = key.removePrefix("firebase_track_")
            val separator = rest.indexOf('_')
            if (separator <= 0 || separator >= rest.lastIndex) continue
            val game = rest.substring(0, separator)
            val characterId = rest.substring(separator + 1)
            if (game in games && characterId.isNotBlank()) {
                desired += characterTopic(characterId)
            }
        }

        val previous = prefs.getStringSet(SUBSCRIPTIONS, emptySet()).orEmpty().toSet()

        (previous - desired).forEach { topic ->
            FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
        }
        (desired - previous).forEach { topic ->
            FirebaseMessaging.getInstance().subscribeToTopic(topic)
        }

        prefs.edit().putStringSet(SUBSCRIPTIONS, desired).apply()
    }

    fun gameTopic(gameId: String) = "gcodus_game_$gameId"

    fun characterTopic(characterId: String) = "gcodus_char_$characterId"
}
