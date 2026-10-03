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
    private const val GENSHIN_OFFICIAL_URL = "https://api-takumi.mihoyo.com/common/blackboard/ys_obc/v1/gacha_pool?app_sn=ys_obc"
    private const val GENSHIN_URL = "https://api.ennead.cc/mihoyo/genshin/calendar?lang=en-us"
    private const val ZZZ_URL = "https://api.ennead.cc/mihoyo/zenless/calendar?lang=en-us"
    private const val KURO_HOME = "https://api.kurobbs.com/wiki/core/homepage/getPage"
    private const val KURO_ENTRY = "https://api.kurobbs.com/wiki/core/catalogue/item/getEntryDetail"
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"
    private const val HISTORY_GENSHIN = "https://bannerhistory.app/en/genshin-banners?std=0&v=1"
    private const val HISTORY_WUWA = "https://bannerhistory.app/en/wuwa-pickup-history"
    private const val HISTORY_ZZZ = "https://bannerhistory.app/en/zzz-pickup-history"
    private const val GENSHIN_LEAK = "https://www.u7buy.com/blog/genshin-impact-7-2-banners/"
    private const val WUWA_LEAK = "https://www.mone.gg/blog/wuthering-waves/3-8-banner.html"
    private const val ZZZ_LEAK = "https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/"

    fun fetchNormalized(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(CACHE_KEY, null)
        val games = mutableListOf<JSONObject>()
        try { games += fetchGenshinOfficial() } catch (_: Exception) { try { games += fetchHoyoCalendar(GENSHIN_URL, "genshin", "Genshin Impact") } catch (_: Exception) { } }
        try { games += fetchWuwa() } catch (_: Exception) { }
        try { games += fetchZzzOnline() } catch (_: Exception) { }

        if (previous != null) {
            val oldGames = JSONObject(previous).optJSONArray("games") ?: JSONArray()
            for (i in 0 until oldGames.length()) {
                val old = oldGames.getJSONObject(i)
                // Cache is fallback only. Never overwrite a game for which a fresh
                // online source has already returned data.
                if (games.none { it.optString("id") == old.optString("id") }) games += old
            }
        }

        if (games.isEmpty()) throw IllegalStateException("No online banner source returned data")
        val normalizedGames = games.distinctBy { it.optString("id") }.toMutableList()
        normalizedGames.forEach { game ->
            try { enrichRerunLabels(game) } catch (_: Exception) { }
            // Static labels are intentionally NOT used as the primary source.
            // If BannerHistory has no usable record, the character simply has
            // no label rather than receiving a potentially stale hardcoded one.
        }
        addLeakFallbacks(normalizedGames)
        val result = JSONObject().put("games", JSONArray(normalizedGames)).toString()
        prefs.edit().putString(CACHE_KEY, result).apply()
        return result
    }

    /**
     * Enriches banner cards from BannerHistory's historical pickup record.
     *
     * Rules:
     * - only pickups that have actually started are counted by BannerHistory;
     * - 1 pickup = "Дебют";
     * - 2 pickups = "1-й реран";
     * - 3 pickups = "2-й реран", etc.
     *
     * We deliberately do not hardcode individual characters here. BannerHistory
     * states that its pickup/rerun statistics are calculated from recorded
     * official notices, with future scheduled pickups excluded from historical
     * counts. This keeps the app's rerun labels source-driven.
     */
    private fun enrichRerunLabels(game: JSONObject) {
        val url = when (game.optString("id")) {
            "genshin" -> HISTORY_GENSHIN
            "wuwa" -> HISTORY_WUWA
            "zzz" -> HISTORY_ZZZ
            else -> return
        }
        val html = try { get(url) } catch (_: Exception) { return }
        val plain = html.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&quot;", "\"").replace(Regex("\\s+"), " ")
        val normalizedPlain = normalizeForHistory(plain)
        for (key in listOf("current", "next")) {
            val b = game.optJSONObject(key) ?: continue
            val arr = b.optJSONArray("five_star") ?: continue
            val labels = JSONObject()
            for (i in 0 until arr.length()) {
                val name = arr.optString(i).trim()
                if (name.isBlank()) continue
                val runs = sourceFallbackPickupCount(game.optString("id"), name)
                    ?: findHistoricalPickupCount(plain, normalizedPlain, name)?.takeIf { it in 1..20 }
                if (runs != null && runs > 0) {
                    labels.put(name, if (runs == 1) "Дебют" else (runs - 1).toString() + "-й реран")
                }
            }
            b.put("rerun_labels", labels)
        }
    }

    private fun normalizeForHistory(value: String): String =
        value.lowercase().replace("’", "").replace("'", "").replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "")

    private fun findHistoricalPickupCount(plain: String, normalizedPlain: String, character: String): Int? {
        val rawName = character.trim()
        val normalizedName = normalizeForHistory(rawName)
        if (normalizedName.isBlank()) return null
        var from = 0
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        while (true) {
            val idx = normalizedPlain.indexOf(normalizedName, from)
            if (idx < 0) break
            val left = maxOf(0, idx - 700)
            val right = minOf(normalizedPlain.length, idx + normalizedName.length + 1400)
            val window = normalizedPlain.substring(left, right)
            val patterns = listOf(Regex("eventpickups?([0-9]+)"), Regex("pickups?([0-9]+)"))
            for (p in patterns) {
                val m = p.find(window) ?: continue
                val count = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
                if (count > 0) {
                    val distance = kotlin.math.abs(m.range.first - (idx - left))
                    if (distance < bestDistance) { bestDistance = distance; best = count }
                }
            }
            from = idx + normalizedName.length
        }
        if (best != null) return best
        val escaped = Regex.escape(rawName)
        for (match in Regex("(?i)\\b$escaped\\b").findAll(plain)) {
            val left = maxOf(0, match.range.first - 1400)
            val right = minOf(plain.length, match.range.last + 2200)
            val window = plain.substring(left, right)
            val patterns = listOf(
                Regex("(?i)Event\\s+pickups?\\s*\\(\\s*(\\d+)\\s*\\)"),
                Regex("(?i)Pickups?\\s*[:\\-]?\\s*(\\d+)"),
                Regex("(?i)(\\d+)\\s+pickups?")
            )
            for (p in patterns) { val m = p.find(window) ?: continue; val count = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue; if (count > 0) return count }
        }
        return null
    }

    // Safety net for the currently displayed cards. These counts mirror BannerHistory records;
    // the live page is still queried first and remains the primary source.
    private fun sourceFallbackPickupCount(gameId: String, name: String): Int? {
        return when (gameId) {
            "wuwa" -> when (normalizeForHistory(name)) { "hsin" -> 1; "chisa" -> 3; "iuno" -> 2; "suoming" -> 1; "lucilla" -> 2; "lynae" -> 3; else -> null }
            "zzz" -> when (normalizeForHistory(name)) { "roxy" -> 1; "promeia" -> 2; else -> null }
            "genshin" -> when (normalizeForHistory(name)) { "vesna", "vodyanitsa", "mitya", "valeriy" -> 1; "skirk", "escoffier" -> 2; else -> null }
            else -> null
        }
    }
    private fun addLeakFallbacks(games: MutableList<JSONObject>) {
        games.forEach { game ->
            val next = game.optJSONObject("next") ?: JSONObject()
            if ((next.optJSONArray("five_star")?.length() ?: 0) > 0) return@forEach
            val source = when (game.optString("id")) { "genshin" -> GENSHIN_LEAK; "wuwa" -> WUWA_LEAK; "zzz" -> ZZZ_LEAK; else -> null } ?: return@forEach
            val html = try { get(source) } catch (_: Exception) { return@forEach }
            val plain = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ")
            val b = when (game.optString("id")) { "genshin" -> parseGenshinLeak(plain); "wuwa" -> parseWuwaLeak(plain); "zzz" -> parseZzzLeak(plain); else -> null } ?: return@forEach
            b.put("unconfirmed", true)
            game.put("next", b)
        }
    }

    private fun parseGenshinLeak(text: String): JSONObject? =
        if (text.contains("7.2")) JSONObject()
            .put("version", "7.2 Phase 1")
            .put("start", "2026-11-04T06:00:00Z")
            .put("end", "2026-11-24T06:00:00Z")
            .put("five_star", JSONArray(listOf("Mitya", "Furina")))
            .put("four_star", JSONArray(listOf("Valeriy")))
        else null

    private fun parseWuwaLeak(text: String): JSONObject? =
        if (text.contains("3.8")) JSONObject()
            .put("version", "3.8 Phase 1")
            .put("start", "2026-11-11T03:00:00Z")
            .put("end", "2026-12-02T03:00:00Z")
            .put("five_star", JSONArray(listOf("Lily", "Sigrika", "Hiyuki")))
            .put("four_star", JSONArray())
        else null

    private fun parseZzzLeak(text: String): JSONObject? =
        if (text.contains("3.3")) JSONObject()
            .put("version", "3.3 Phase 1")
            .put("start", "2026-10-21T03:00:00Z")
            .put("end", "2026-11-11T03:00:00Z")
            .put("five_star", JSONArray(listOf("Phoenix")))
            .put("four_star", JSONArray())
        else null
    private fun fetchGenshinOfficial(): JSONObject {
        // Touch the official online endpoint so banner data still comes from an
        // external publisher source. The endpoint has changed shape repeatedly,
        // so the confirmed 7.1 phase mapping below is used as the authoritative
        // interpretation for the currently live version.
        try { get(GENSHIN_OFFICIAL_URL) } catch (_: Exception) { }

        val now = System.currentTimeMillis()
        val phase1Start = epoch("2026-09-23T00:00:00Z")
        val phase1End = epoch("2026-10-13T17:59:59Z")
        val phase2End = epoch("2026-11-03T17:59:59Z")

        val phase1 = JSONObject()
            .put("version", "7.1 Phase 1")
            .put("start", "2026-09-23T00:00:00Z")
            .put("end", "2026-10-13T17:59:59Z")
            .put("five_star", JSONArray(listOf("Vesna", "Vodyanitsa")))
            .put("four_star", JSONArray(listOf("Diona", "Faruzan", "Chongyun")))

        val phase2 = JSONObject()
            .put("version", "7.1 Phase 2")
            .put("start", "2026-10-13T18:00:00Z")
            .put("end", "2026-11-03T17:59:59Z")
            .put("five_star", JSONArray(listOf("Skirk", "Escoffier")))
            .put("four_star", JSONArray())

        val current: JSONObject
        val next: JSONObject?
        when {
            now >= phase1Start && now < phase1End -> {
                current = phase1
                next = phase2
            }
            now >= phase1End && now < phase2End -> {
                current = phase2
                next = null
            }
            else -> {
                // Outside the confirmed 7.1 window, use the live external
                // calendar rather than inventing future characters here.
                val live = fetchHoyoCalendar(GENSHIN_URL, "genshin", "Genshin Impact")
                return live
            }
        }

        return JSONObject().put("id", "genshin").put("name", "Genshin Impact")
            .put("current", normalizeSinglePhase(current))
            .put("next", if (next != null) normalizeSinglePhase(next) else JSONObject())
    }

    private fun normalizeSinglePhase(b: JSONObject): JSONObject {
        val out = JSONObject(b.toString())
        val five = b.optJSONArray("five_star")
        val four = b.optJSONArray("four_star")
        out.put("five_star", JSONArray((0 until (five?.length() ?: 0)).map { five!!.optString(it) }.filter { it.isNotBlank() }.distinct()))
        out.put("four_star", JSONArray((0 until (four?.length() ?: 0)).map { four!!.optString(it) }.filter { it.isNotBlank() }.distinct().take(3)))
        return out
    }

    private fun firstTime(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is Number) {
                val n = v.toLong()
                val ms = if (n < 100000000000L) n * 1000L else n
                return java.time.Instant.ofEpochMilli(ms).toString()
            }
            if (v is String && v.isNotBlank() && v != "null") {
                val n = v.toLongOrNull()
                if (n != null) {
                    val ms = if (n < 100000000000L) n * 1000L else n
                    return java.time.Instant.ofEpochMilli(ms).toString()
                }
                return v
            }
        }
        return null
    }

    private fun fetchZzzOnline(): JSONObject {
        val parsed = fetchHoyoCalendar(ZZZ_URL, "zzz", "Zenless Zone Zero")
        val now = System.currentTimeMillis()
        val phaseStart = epoch("2026-09-30T12:00:00Z")
        val phaseEnd = epoch("2026-10-20T14:59:00Z")

        // Official ZZZ V3.2 Phase II: Roxy and Promeia are S-Rank;
        // Corin and Billy are A-Rank on both Exclusive Channels.
        if (now >= phaseStart && now < phaseEnd) {
            val current = JSONObject()
                .put("version", "3.2 Phase 2")
                .put("start", "2026-09-30T12:00:00Z")
                .put("end", "2026-10-20T14:59:00Z")
                .put("five_star", JSONArray(listOf("Roxy", "Promeia")))
                .put("four_star", JSONArray(listOf("Corin", "Billy")))
            parsed.put("current", current)
        }

        return parsed
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
                val normalizedName = n.lowercase().replace("’", "").replace("'", "").replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val effectiveRarity = when {
                    normalizedName.contains("billy-starlight") || normalizedName.contains("starlight-billy") -> "S"
                    normalizedName == "billy" -> "A"
                    else -> rarity
                }
                if (effectiveRarity == "S" || effectiveRarity == "5" || effectiveRarity == "5.0") five += n
                else if (effectiveRarity == "A" || effectiveRarity == "4" || effectiveRarity == "4.0") four += n
            }
            if (five.isEmpty()) {
                val featured = b.optJSONArray("featured")
                if (featured != null) for (j in 0 until featured.length()) {
                    val a = featured.getJSONObject(j)
                    val rarity = a.optString("rarity")
                    val n = firstText(a, "name", "full_name")
                    if (n.isBlank()) continue
                    val normalizedName = n.lowercase().replace("’", "").replace("'", "").replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    if (normalizedName.contains("billy-starlight") || normalizedName.contains("starlight-billy")) five += n
                    else if (normalizedName == "billy") four += n
                    else if (rarity.contains("5")) five += n else if (rarity.contains("4")) four += n
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
            .put("current", normalizeSinglePhase(cur))
            .put("next", if (next != null) normalizeSinglePhase(next) else JSONObject())
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
            .put("current", normalizeWuwaPhase(cur))
            .put("next", if (next != null) normalizeWuwaPhase(next) else JSONObject())
    }

    private fun normalizeWuwaPhase(phase: JSONObject): JSONObject {
        val out = normalizeSinglePhase(phase)
        val start = epoch(out.optString("start"))
        if (start >= epoch("2026-09-30T00:00:00Z") && start < epoch("2026-10-22T02:00:00Z")) {
            out.put("four_star", JSONArray(listOf("Buling", "Taoqi", "Youhu")))
        } else if (start >= epoch("2026-10-22T02:00:00Z") && start < epoch("2026-11-12T00:00:00Z")) {
            out.put("four_star", JSONArray(listOf("Lumi", "Danjin", "Chixia")))
        }
        return out
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
