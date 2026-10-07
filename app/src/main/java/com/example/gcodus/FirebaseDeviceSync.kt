package com.example.gcodus

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Registers this installation as a Firebase device and keeps its notification
 * preferences in Firestore. FCM delivery does not depend on the activity being
 * open.
 */
object FirebaseDeviceSync {
    private const val TAG = "GcodusFirebase"
    private const val PREFS = "g_codus"

    private val games = listOf("genshin", "wuwa", "zzz", "starrail", "endfield")

    fun sync(context: Context) {
        val app = context.applicationContext
        val auth = FirebaseAuth.getInstance()

        fun getTokenAndSave() {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> saveDevice(app, auth, token) }
                .addOnFailureListener { error -> Log.w(TAG, "FCM token unavailable", error) }
        }

        if (auth.currentUser != null) {
            getTokenAndSave()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { getTokenAndSave() }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Anonymous Firebase auth failed. Enable Anonymous sign-in in Firebase Console.", error)
                }
        }
    }

    fun onToken(context: Context, token: String) {
        val app = context.applicationContext
        val auth = FirebaseAuth.getInstance()

        if (auth.currentUser != null) {
            saveDevice(app, auth, token)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { saveDevice(app, auth, token) }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Could not authenticate refreshed FCM token", error)
                }
        }
    }

    private fun saveDevice(context: Context, auth: FirebaseAuth, token: String) {
        val uid = auth.currentUser?.uid ?: return
        if (token.isBlank()) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val selectedGames = games.filter { prefs.getBoolean("favorite_$it", false) }
        // Store game-scoped tracked identities so character IDs cannot collide between games.
        val trackedCharacters = prefs.all.entries.asSequence()
            .filter { it.key.startsWith("tracked_v2_") && it.value == true }
            .mapNotNull { entry ->
                val rest = entry.key.removePrefix("tracked_v2_")
                val separator = rest.indexOf('_')
                if (separator <= 0 || separator >= rest.lastIndex) null
                else {
                    val game = rest.substring(0, separator)
                    val character = rest.substring(separator + 1)
                    if (game in games && character.isNotBlank()) "$game|$character" else null
                }
            }
            .distinct()
            .toList()

        val data = hashMapOf<String, Any>(
            "token" to token,
            "games" to selectedGames,
            "characters" to trackedCharacters,
            "updatedAt" to FieldValue.serverTimestamp(),
            "platform" to "android",
            "appVersion" to "0.6.9"
        )

        FirebaseFirestore.getInstance()
            .collection("devices")
            .document(uid)
            .set(data, SetOptions.merge())
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to sync device to Firestore", error)
            }
    }
}
