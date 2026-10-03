package com.example.gcodus

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

data class OnlineCharacter(
    val gameId: String,
    val name: String,
    val slug: String,
    val announced: Boolean,
    val portraitUrl: String
)

object CharacterDatabase {
    private const val WUWA_CHARACTERS = "https://www.prydwen.gg/wuthering-waves/characters"
    private const val ZZZ_CHARACTERS = "https://www.prydwen.gg/zenless/characters"
    private const val WUWA_HISTORY = "https://bannerhistory.app/en/wuwa-pickup-history"
    private const val ZZZ_HISTORY = "https://bannerhistory.app/en/zzz-pickup-history"
    private const val GENSHIN_CHARACTERS = "https://www.prydwen.gg/genshin-impact/characters"
    private const val GENSHIN_HISTORY = "https://bannerhistory.app/en/genshin-banners?std=0&v=1"

    fun fetch(context: Context): List<OnlineCharacter> {
        val result = mutableListOf<OnlineCharacter>()
        result += fetchGame("wuwa", WUWA_CHARACTERS, WUWA_HISTORY)
        result += fetchGame("zzz", ZZZ_CHARACTERS, ZZZ_HISTORY)
        result += fetchGame("genshin", GENSHIN_CHARACTERS, GENSHIN_HISTORY)
        result += OnlineCharacter("wuwa", "Lumi", "lumi", false, wuwaPortrait("lumi"))
        result += OnlineCharacter("wuwa", "Youhu", "youhu", false, wuwaPortrait("youhu"))
        return result.distinctBy { it.gameId + "|" + it.slug }
    }

    private fun fetchGame(gameId: String, listUrl: String, historyUrl: String): List<OnlineCharacter> {
        val html = get(listUrl)
        val history = try { get(historyUrl) } catch (_: Exception) { "" }
        val historyKnown = normalize(history)
        val pattern = Regex("href=[\\\"]/(?:wuthering-waves|zenless|genshin-impact)/characters/([^\\\"]+)[\\\"][^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
        val result = mutableListOf<OnlineCharacter>()
        for (m in pattern.findAll(html)) {
            val slug = m.groupValues[1].substringBefore("?").trim('/').lowercase()
            val rawName = m.groupValues[2].replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").trim()
            val name = cleanName(rawName)
            if (slug.isBlank() || name.isBlank() || name.length > 80) continue
            val announced = historyKnown.isNotEmpty() && !historyKnown.contains(normalize(name))
            result += OnlineCharacter(gameId, name, slug, announced, portraitUrl(gameId, slug))
        }
        return result.distinctBy { it.slug }
    }

    private fun portraitUrl(gameId: String, slug: String): String =
        when (gameId) {
            "wuwa" -> wuwaPortrait(slug)
            "zzz" -> "https://cdn.prydwen.gg/images/zzz/characters/card_" + slug + ".webp"
            "genshin" -> "https://cdn.prydwen.gg/images/genshin-impact/characters/" + slug + "_full.webp"
            else -> ""
        }

    private fun wuwaPortrait(slug: String): String =
        "https://cdn.prydwen.gg/images/ww/characters/card_" + slug + ".webp"

    private fun cleanName(value: String): String {
        var s = value.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("(New|[0-9]+\\.[0-9]+)$"), "").trim()
        return s
    }

    private fun normalize(value: String): String =
        value.lowercase().replace("’", "").replace("'", "").replace("&", "and").replace(Regex("[^a-z0-9]+"), "")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
