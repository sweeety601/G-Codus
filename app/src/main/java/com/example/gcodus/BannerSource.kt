package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local banner database.
 *
 * Banner metadata is read only from the bundled banner_feed.json.
 * There is no online banner database and no GitHub/network refresh here.
 */
object BannerSource {
    fun fetchNormalized(context: Context): String {
        return context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
    }
}
