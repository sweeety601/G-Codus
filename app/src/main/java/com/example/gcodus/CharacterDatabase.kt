package com.example.gcodus

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

data class OnlineCharacter(
    val gameId: String,
    val name: String,
    val slug: String,
    val announced: Boolean,
    val portraitUrl: String,
    val rarity: Int = 0
)

object CharacterDatabase {
    private const val WUWA_CHARACTERS = "https://www.prydwen.gg/wuthering-waves/characters"
    private const val ZZZ_CHARACTERS = "https://www.prydwen.gg/zenless/characters"
    private const val GENSHIN_CHARACTERS = "https://www.prydwen.gg/genshin-impact/characters"
    private const val HSR_CHARACTERS = "https://www.prydwen.gg/star-rail/characters"
    private const val ENDFIELD_CHARACTERS = "https://www.prydwen.gg/arknights-endfield/characters"

    private val PROTAGONIST_SLUGS = mapOf(
        "genshin" to setOf("traveler", "aether", "lumine", "traveller"),
        "wuwa" to setOf("rover"),
        "zzz" to setOf("belle", "wise", "proxy"),
        "starrail" to setOf("trailblazer"),
        "endfield" to setOf("endministrator")
    )

    private val ANNOUNCED_ONLY = mapOf(
        "genshin" to setOf("mitya", "valeriy"),
        "wuwa" to setOf("suoming"),
        "zzz" to emptySet(),
        "starrail" to emptySet(),
        "endfield" to emptySet()
    )

    fun fetch(context: Context): List<OnlineCharacter> {
        val result = mutableListOf<OnlineCharacter>()
        result += fetchGame("wuwa", WUWA_CHARACTERS)
        result += fetchGame("zzz", ZZZ_CHARACTERS)
        result += fetchGame("genshin", GENSHIN_CHARACTERS)
        result += fetchGame("starrail", HSR_CHARACTERS)
        result += fetchGame("endfield", ENDFIELD_CHARACTERS)

        // Static roster fallback compiled from the current public database lists.
        // This keeps the Wishlist usable even when a character-list page is down.
        result += fallbackCharacters("starrail", HSR_FALLBACK)
        result += fallbackCharacters("endfield", ENDFIELD_FALLBACK)

        // Prydwen uses "Billy" for the ordinary playable agent. In G-Codus
        // this is displayed as the official character name "Billy Kid".
        if (result.none { it.gameId == "zzz" && normalize(it.slug) == "billy" }) {
            result += OnlineCharacter("zzz", "Billy Kid", "billy", false, portraitUrl("zzz", "billy"))
        } else {
            for (i in result.indices) {
                val c = result[i]
                if (c.gameId == "zzz" && normalize(c.slug) == "billy") {
                    result[i] = c.copy(name = "Billy Kid")
                }
            }
        }

        return result.filterNot { isProtagonist(it.gameId, it.slug, it.name) }.distinctBy { canonicalKey(it.gameId, it.slug, it.name) }
    }

    private fun fetchGame(gameId: String, listUrl: String): List<OnlineCharacter> {
        val html = try { get(listUrl) } catch (_: Exception) { return emptyList() }
        val pattern = Regex(
            "href=[\\\"]/(?:wuthering-waves|zenless|genshin-impact)/characters/([^\\\"?#/]+)[\\\"][^>]*>(.*?)</a>",
            RegexOption.IGNORE_CASE
        )
        val result = mutableListOf<OnlineCharacter>()

        for (m in pattern.findAll(html)) {
            val slug = m.groupValues[1].trim('/').lowercase()
            val rawName = m.groupValues[2]
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ")
                .trim()
            val name = cleanName(rawName)

            // Prydwen's character page contains navigation/metadata links too.
            // Accept only short, human-readable character names; this removes
            // the junk that previously leaked into the Genshin roster.
            if (!isValidCharacterEntry(slug, name)) continue
            if (isProtagonist(gameId, slug, name)) continue

            val announced = normalize(slug) in ANNOUNCED_ONLY[gameId].orEmpty() ||
                normalize(name) in ANNOUNCED_ONLY[gameId].orEmpty()
            result += OnlineCharacter(
                gameId,
                if (gameId == "zzz" && normalize(slug) == "billy") "Billy Kid" else name,
                slug,
                announced,
                portraitUrl(gameId, slug)
            )
        }

        return result
            .groupBy { canonicalKey(it.gameId, it.slug, it.name) }
            .values
            .map { it.first() }
    }

    private fun isValidCharacterEntry(slug: String, name: String): Boolean {
        if (slug.isBlank() || name.isBlank() || slug.length > 48 || name.length > 60) return false
        if (!slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) return false
        if (name.split(Regex("\\s+")).size > 5) return false
        if (!name.matches(Regex("[A-Za-zÀ-ÿ0-9][A-Za-zÀ-ÿ0-9 .:'&()\\-]*"))) return false

        val bad = listOf(
            "tier list", "build", "guide", "teams", "team", "stats", "overview",
            "weapons", "weapon", "artifacts", "echoes", "materials", "talents",
            "skills", "constellations", "abilities", "best", "review", "database",
            "characters", "characters list", "news", "home", "login"
        )
        val n = name.lowercase()
        if (bad.any { n == it || n.contains(it) }) return false
        return true
    }

    private fun isProtagonist(gameId: String, slug: String, name: String): Boolean {
        val normalizedSlug = normalize(slug)
        val normalizedName = normalize(name)
        return PROTAGONIST_SLUGS[gameId].orEmpty().any { val p = normalize(it); normalizedSlug == p || normalizedName == p || normalizedSlug.startsWith(p) || normalizedName.startsWith(p) }
    }

    private fun canonicalKey(gameId: String, slug: String, name: String): String {
        if (gameId == "starrail") {
            val s = normalize(slug)
            val n = normalize(name)
            if (s == "blademortenax" || s == "mortenaxblade" || n == "blademortenax" || n == "mortenaxblade") return "starrail|mortenaxblade"
            if (s == "imbibitorlunae" || s == "danhengimbibitorlunae" ||
                n == "imbibitorlunae" || n == "danhengimbibitorlunae") return "starrail|imbibitorlunae"
            if (s == "topaz" || s == "topazandnumby" ||
                n == "topaz" || n == "topazandnumby") return "starrail|topaz"
        }
        if (gameId == "zzz") {
            val s = normalize(slug)
            val n = normalize(name)
            if (s == "billy" || s == "billykid" || n == "billy" || n == "billykid") return "zzz|billy"
        }
        return "$gameId|" + normalize(slug).ifBlank { normalize(name) }
    }

    private fun portraitUrl(gameId: String, slug: String): String =
        when (gameId) {
            "wuwa" -> wuwaPortrait(slug)
            "zzz" -> "https://cdn.prydwen.gg/images/zzz/characters/card_" + slug + ".webp"
            "genshin" -> "https://cdn.prydwen.gg/images/genshin-impact/characters/" + slug + "_full.webp"
            "starrail" -> "https://cdn.prydwen.gg/images/star-rail/characters/card_" + slug + ".webp"
            "endfield" -> "https://cdn.prydwen.gg/images/arknights-endfield/characters/card_" + slug + ".webp"
            else -> ""
        }

    private fun wuwaPortrait(slug: String): String =
        "https://cdn.prydwen.gg/images/ww/characters/card_" + slug + ".webp"

    private fun fallbackCharacters(gameId: String, names: List<String>): List<OnlineCharacter> =
        names.map { name ->
            val slug = name.lowercase()
                .replace("’", "")
                .replace("'", "")
                .replace("•", "-")
                .replace("&", "and")
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            OnlineCharacter(
                gameId, name, slug, false, portraitUrl(gameId, slug),
                if (gameId == "endfield") endfieldRarity(name) else 0
            )
        }

    private fun endfieldRarity(name: String): Int {
        val n = normalize(name)
        return when {
            n in ENDFIELD_6_STAR -> 6
            n in ENDFIELD_5_STAR -> 5
            n in ENDFIELD_4_STAR -> 4
            else -> 0
        }
    }

    // HSR roster assembled from the live Prydwen character database.
    private val HSR_FALLBACK = listOf(
        "Acheron","Aglaea","Anaxa","Archer","Argenti","Arlan","Ashveil","Asta",
        "Aventurine","Aventurine Waveflair","Bailu","Black Swan","Blade","Boothill",
        "Bronya","Castorice","Cerydra","Cipher","Clara","Cyrene","Dan Heng",
        "Dan Heng • Imbibitor Lunae","Dan Heng • Permansor Terrae","Dr. Ratio",
        "Evanescia","Feixiao","Firefly","Fu Xuan","Gallagher","Gepard","Gilgamesh",
        "Guinaifen","Hanya","Herta","Himeko","Himeko Nova","Hook","Huohuo",
        "Hyacine","Hysilens","Jade","Jiaoqiu","Jing Yuan","Jingliu","Kafka",
        "Lingsha","Luka","Luocha","Lynx","March 7th","March 7th • Evernight",
        "March 7th • The Hunt","Misha","Mortenax Blade","Moze","Mydei","Natasha",
        "Pearl","Pela","Phainon","Qingque","Rappa","Rin Tohsaka","Robin",
        "Robin Summeretto","Ruan Mei","Saber","Sampo","Seele","Serval",
        "Silver Wolf","Silver Wolf • Lv. 999","Sparkle","Sparxie","Sunday",
        "Sushang","The Dahlia","The Herta","Tingyun","Tingyun • Fugue",
        "Topaz & Numby","Trailblazer • Destruction","Trailblazer • Elation",
        "Trailblazer • Harmony","Trailblazer • Preservation","Trailblazer • Remembrance",
        "Tribbie","Welt","Xueyi","Yanqing","Yao Guang","Yukong","Yunli"
    )

    private val ENDFIELD_6_STAR = setOf(
        "arcane","ardelia","camille","ember","endministrator","gilberta","laevatain",
        "last rite","lifeng","liino","mi fu","pogranichnik","rossi","tangtang",
        "typhoeus","yvonne","zhuang fangyi"
    ).map(::normalize).toSet()

    private val ENDFIELD_5_STAR = setOf(
        "alesh","arclight","avywenna","chen qianyu","da pan","perlica","purrchena",
        "snowshine","wulfgard","xaihi"
    ).map(::normalize).toSet()

    private val ENDFIELD_4_STAR = setOf(
        "akekuri","antal","catcher","estella","fluorite"
    ).map(::normalize).toSet()

    // Endfield roster from the current Prydwen operator database.
    // Purrchena is included from the public free-operator roster.
    private val ENDFIELD_FALLBACK = listOf(
        "Akekuri","Alesh","Antal","Arcane","Arclight","Ardelia","Avywenna","Camille",
        "Catcher","Chen Qianyu","Da Pan","Ember","Endministrator","Estella","Fluorite",
        "Gilberta","Laevatain","Last Rite","Lifeng","Liino","Mi Fu","Perlica",
        "Pogranichnik","Purrchena","Rossi","Si","Snowshine","Tangtang","Typhoeus",
        "Wulfgard","Xaihi","Yvonne","Zhuang Fangyi"
    )

    private fun cleanName(value: String): String {
        var s = value.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("\\b(New|[0-9]+\\.[0-9]+)\\b"), "").trim()
        return s
    }

    private fun normalize(value: String): String =
        value.lowercase().replace("’", "").replace("'", "")
            .replace("&", "and").replace(Regex("[^a-z0-9]+"), "")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
