package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Online banner provider. Banner metadata is never read from GitHub.
 * Portraits remain strictly local/bundled in the APK.
 */
object BannerSource {
    private const val GENSHIN_URL = "https://api.ennead.cc/mihoyo/genshin/calendar?lang=en-us"
    private const val ZZZ_URL = "https://api.ennead.cc/mihoyo/zenless/calendar?lang=en-us"
    private const val KURO_HOME = "https://api.kurobbs.com/wiki/core/homepage/getPage"
    private const val KURO_ENTRY = "https://api.kurobbs.com/wiki/core/catalogue/item/getEntryDetail"
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"

    fun fetchNormalized(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(CACHE_KEY, null)
        val games = mutableListOf<JSONObject>()
        try { games += fetchHoyoCalendar(GENSHIN_URL, "genshin", "Genshin Impact") } catch (_: Exception) { }
        try { games += fetchWuwa() } catch (_: Exception) { }
        try { games += fetchHoyoCalendar(ZZZ_URL, "zzz", "Zenless Zone Zero") } catch (_: Exception) }

        if (previous != null) {
            val oldGames = JSONObject(previous).optJSONArray("games") ?: JSONArray()
            for (i in 0 until oldGames.length()) {
                val old = oldGames.getJSONObject(i)
                if (games.none { it.optString("id") == old.optString("id") }) games += old
            }
        }

        if (games.isEmpty()) throw IllegalStateException("No online banner source returned data")
        val result = JSONObject().put("games", JSONArray(games.distinctBy { it.optString("id") })).toString()
        prefs.edit().putString(CACHE_KEY, result).apply()
        return result
    }

    private fun fetchHoyoCalendar(url: String, id: String, name: String): JSONObject {
        val root = JSONObject(get(url))
        val banners = root.optJSONArray("banners") ?: throw IllegalStateException("No banners: $id")
        val parsed = mutableListOf<JSONObject>()
        for (i in 0 until banners.length()) {
            val b = banners.getJSONObject(i)
            val start = readTime(b, "start_time") ?: continue
            val end = readTime(b, "end_time") ?: continue
            val five = mutableListOf<String>()
            val four = mutableListOf<String>()
            val agents = b.optJSONArray("agents") ?: b.optJSONArray("characters")
            if (agents != null) for (j in 0 until agents.length()) {
                val a = agents.getJSONObject(j)
                val rarity = a.optString("rarity").uppercase()
                val n = firstText(a, "name", "full_name")
                if (n.isBlank()) continue
                if (rarity == "S" || rarity == "5" || rarity == "5.0") five += n
                else if (rarity == "A" || rarity == "4" || rarity == "4.0") four += n
            }
            if (five.isEmpty()) {
                val featured = b.optJSONArray("featured")
                if (featured != null) for (j in 0 until featured.length()) {
                    val a = featured.getJSONObject(j)
                    val rarity = a.optString("rarity")
                    val n = firstText(a, "name", "full_name")
                    if (n.isBlank()) continue
                    if (rarity.contains("5")) five += n else if (rarity.contains("4")) four += n
                }
            }
            if (five.isEmpty()) continue
            parsed += JSONObject().put("version", b.optString("version"))
                .put("start", start).put("end", end)
                .put("five_star", JSONArray(five.distinct()))
                .put("four_star", JSONArray(four.distinct()))
        }
        if (parsed.isEmpty()) throw IllegalStateException("No character banners: $id")
        parsed.sortBy { it.optString("start") }
        val now = System.currentTimeMillis()
        val current = parsed.firstOrNull { epoch(it.optString("start")) <= now && now < epoch(it.optString("end")) }
        val next = parsed.firstOrNull { epoch(it.optString("start")) > now }
        val cur = current ?: parsed.lastOrNull { epoch(it.optString("start")) <= now } ?: parsed.first()
        return JSONObject().put("id", id).put("name", name)
            .put("current", cur).put("next", next ?: JSONObject())
    }

    private fun fetchWuwa(): JSONObject {
        val root = JSONObject(post(KURO_HOME, mapOf(
            "wiki_type" to "9",
            "source" to "h5",
            "referer" to "https://wiki.kurobbs.com/"
        ), ""))
        var raw: Any? = root.optJSONObject("data")?.opt("contentJson")
        if (raw is String) raw = JSONObject(raw)
        val content = raw as? JSONObject ?: throw IllegalStateException("Kuro contentJson missing")
        val modules = content.optJSONArray("sideModules") ?: throw IllegalStateException("Kuro sideModules missing")
        val parsed = mutableListOf<JSONObject>()
        for (i in 0 until modules.length()) {
            val module = modules.optJSONObject(i) ?: continue
            val title = module.optString("title")
            if (!title.contains("角色活动") && !title.contains("角色活動") && !title.contains("喚取") && !title.contains("唤取")) continue
            val tabs = module.optJSONObject("content")?.optJSONArray("tabs") ?: continue
            for (j in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(j) ?: continue
                val range = tab.optJSONObject("countDown")?.optJSONArray("dateRange") ?: continue
                if (range.length() < 2) continue
                val start = normalizeKuroTime(range.optString(0)) ?: continue
                val end = normalizeKuroTime(range.optString(1)) ?: continue
                val imgs = tab.optJSONArray("imgs") ?: JSONArray()
                val entryId = imgs.optJSONObject(0)?.optJSONObject("linkConfig")?.optString("entryId")
                if (entryId.isNullOrBlank()) continue
                val detail = try { JSONObject(post(KURO_ENTRY, emptyMap(), "id=" + entryId)) } catch (_: Exception) { continue }
                val five = detail.optJSONObject("data")?.optString("name").orEmpty()
                if (five.isBlank()) continue
                parsed += JSONObject().put("version", tab.optString("version"))
                    .put("start", start).put("end", end)
                    .put("five_star", JSONArray().put(five))
                    .put("four_star", extractFourStars(tab))
            }
        }
        if (parsed.isEmpty()) throw IllegalStateException("No Kuro character banners")
        val unique = parsed.distinctBy { it.optString("start") + "|" + it.optString("five_star") }.sortedBy { it.optString("start") }
        val now = System.currentTimeMillis()
        val cur = unique.firstOrNull { epoch(it.optString("start")) <= now && now < epoch(it.optString("end")) }
            ?: unique.lastOrNull { epoch(it.optString("start")) <= now } ?: unique.first()
        val next = unique.firstOrNull { epoch(it.optString("start")) > now }
        return JSONObject().put("id", "wuwa").put("name", "Wuthering Waves")
            .put("current", cur).put("next", next ?: JSONObject())
    }

    private fun extractFourStars(tab: JSONObject): JSONArray {
        val names = linkedSetOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val rarity = v.optString("rarity").uppercase()
                    val n = firstText(v, "name", "characterName")
                    if ((rarity == "4" || rarity == "4.0" || rarity == "A") && n.isNotBlank()) names += n
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(tab)
        return JSONArray(names.toList())
    }

    private fun firstText(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun normalizeKuroTime(value: String): String? {
        val s = value.trim().replace(' ', 'T')
        if (s.isBlank()) return null
        return if (s.count { it == ':' } == 1) "$s:00" else s
    }

    private fun readTime(o: JSONObject, key: String): String? {
        return when (val v = o.opt(key)) {
            is Number -> OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(v.toLong()), ZoneOffset.UTC).toString()
            is String -> v.trim().takeIf { it.isNotBlank() && it != "null" }
            else -> null
        }
    }

    private fun epoch(s: String): Long = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try { java.time.LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { 0L }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 20000; c.doOutput = true
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body.isNotEmpty()) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
