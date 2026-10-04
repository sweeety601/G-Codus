from pathlib import Path

# Endfield has 6★ / 5★ / 4★ operators.
p = Path('app/src/main/java/com/example/gcodus/CharacterDatabase.kt')
s = p.read_text()
s = s.replace(
    '''data class OnlineCharacter(
    val gameId: String,
    val name: String,
    val slug: String,
    val announced: Boolean,
    val portraitUrl: String
)''',
    '''data class OnlineCharacter(
    val gameId: String,
    val name: String,
    val slug: String,
    val announced: Boolean,
    val portraitUrl: String,
    val rarity: Int = 0
)'''
)
old = '''    private fun fallbackCharacters(gameId: String, names: List<String>): List<OnlineCharacter> =
        names.map { name ->
            val slug = name.lowercase()
                .replace("’", "")
                .replace("'", "")
                .replace("•", "-")
                .replace("&", "and")
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            OnlineCharacter(gameId, name, slug, false, portraitUrl(gameId, slug))
        }
'''
new = '''    private fun fallbackCharacters(gameId: String, names: List<String>): List<OnlineCharacter> =
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
'''
if old not in s: raise SystemExit('CharacterDatabase fallback block not found')
s = s.replace(old, new)
marker = '    // Endfield roster from the current Prydwen operator database.\n'
rarity = '''    private val ENDFIELD_6_STAR = setOf(
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

'''
if marker not in s: raise SystemExit('Endfield marker not found')
s = s.replace(marker, rarity + marker)
p.write_text(s)

# HSR codes: use hoyo-codes first and accept both rewards/reward formats.
p = Path('app/src/main/java/com/example/gcodus/PromoCodeSource.kt')
s = p.read_text()
s = s.replace(
    '''"starrail" to listOf(
            "OpenGachaCodes" to "https://api.ennead.cc/codes/starrail"
        ),''',
    '''"starrail" to listOf(
            "hoyo-codes" to "https://hoyo-codes.seria.moe/codes?game=hkrpg",
            "OpenGachaCodes" to "https://api.ennead.cc/codes/starrail"
        ),'''
)
old = 'val rewards = formatRewards(item.opt("rewards"))'
new = '''val rewards = formatRewards(
                        item.opt("rewards").takeUnless { it == null || it == JSONObject.NULL || it.toString().equals("unknown", true) }
                            ?: item.opt("reward").takeUnless { it == null || it == JSONObject.NULL || it.toString().equals("unknown", true) }
                            ?: item.opt("items").takeUnless { it == null || it == JSONObject.NULL }
                            ?: item.opt("description")
                    )'''
if old not in s: raise SystemExit('Promo reward line not found')
s = s.replace(old, new)
p.write_text(s)

# Home game buttons: horizontal scrolling instead of squeezing five games.
p = Path('app/src/main/java/com/example/gcodus/MainActivity.kt')
s = p.read_text()
old = '''        val icons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(2))
        }

        gameMeta.forEach { meta ->'''
new = '''        val icons = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(14), 0, dp(2))
        }
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        icons.addView(iconRow, HorizontalScrollView.LayoutParams(-2, dp(100)))

        gameMeta.forEach { meta ->'''
if old not in s: raise SystemExit('Top icon block start not found')
s = s.replace(old, new)
old = '            icons.addView(item, LinearLayout.LayoutParams(0, dp(94), 1f))\n        }\n\n        wrapper.addView(icons)'
new = '            iconRow.addView(item, LinearLayout.LayoutParams(dp(94), dp(94)))\n        }\n\n        wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(104)))'
if old not in s: raise SystemExit('Top icon block end not found')
s = s.replace(old, new)

needle = '''        cell.addView(label(character.name, 11.5f, text, true).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = true
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }, LinearLayout.LayoutParams(-1, dp(58)))
'''
repl = '''        val endfieldRarity = if (character.gameId == "endfield") endfieldRarityForDisplay(character.name) else 0
        if (endfieldRarity > 0) {
            cell.addView(label(endfieldRarity.toString() + "★", 11f, Color.rgb(255, 211, 76), true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }
        cell.addView(label(character.name, 11.5f, text, true).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = true
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }, LinearLayout.LayoutParams(-1, if (endfieldRarity > 0) dp(50) else dp(58)))
'''
if needle not in s: raise SystemExit('Tracking card label block not found')
s = s.replace(needle, repl)
marker = '    private fun trackingKey(gameId: String, file: String) = '
helper = '''    private fun endfieldRarityForDisplay(name: String): Int {
        val n = name.lowercase().replace("’", "").replace("'", "").replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "")
        return when {
            n in setOf("arcane","ardelia","camille","ember","endministrator","gilberta","laevatain","lastrite","lifeng","liino","mifu","pogranichnik","rossi","tangtang","typhoeus","yvonne","zhuangfangyi") -> 6
            n in setOf("alesh","arclight","avywenna","chenqianyu","dapan","perlica","purrchena","snowshine","wulfgard","xaihi") -> 5
            n in setOf("akekuri","antal","catcher","estella","fluorite") -> 4
            else -> 0
        }
    }

'''
if marker not in s: raise SystemExit('Tracking key marker not found')
s = s.replace(marker, helper + marker)
old = '''                PromoCode(
                    o.optString("game"), o.optString("code"), o.optString("rewards"),
                    o.optString("source"), o.optString("expires_at"),
                    o.optString("expired_at"), if (expired) "expired" else "active"
                )'''
new = '''                PromoCode(
                    o.optString("game"), o.optString("code"),
                    listOf(o.optString("rewards"), o.optString("reward"), o.optString("items"), o.optString("description"))
                        .firstOrNull { it.isNotBlank() && !it.equals("unknown", true) } ?: "",
                    o.optString("source"), o.optString("expires_at"),
                    o.optString("expired_at"), if (expired) "expired" else "active"
                )'''
if old not in s: raise SystemExit('parseCodesFeed block not found')
s = s.replace(old, new)
p.write_text(s)

# Leak-source metadata for HSR and Endfield. It never expands the UI beyond current + next.
p = Path('scripts/sync_banners.py')
s = p.read_text()
leak_block = '''\nLEAK_SOURCES = {\n    "Honkai: Star Rail": [\n        "https://hsr.hakush.in/",\n        "https://www.reddit.com/r/HonkaiStarRail_leaks/",\n    ],\n    "Arknights: Endfield": [\n        "https://www.reddit.com/r/ArknightsEndfieldLeak/",\n        "https://www.pocketgamer.com/arknights-endfield/upcoming-banners/",\n    ],\n}\n'''
if 'LEAK_SOURCES = {' not in s:
    s = s.replace('\nUA = "G-Codus/2.1 banner-sync"\n', leak_block + '\nUA = "G-Codus/2.1 banner-sync"\n')
old = 'output["games"][game]={"source_url":url,"fetched_at":datetime.now(timezone.utc).isoformat(),"status":"source_reachable" if cards else "source_unavailable","current":current[:6],"next":next_phase[:6],"upcoming":[]}'
new = 'output["games"][game]={"source_url":url,"fetched_at":datetime.now(timezone.utc).isoformat(),"status":"source_reachable" if cards else "source_unavailable","current":current[:6],"next":next_phase[:6],"upcoming":[],"leak_sources":LEAK_SOURCES.get(game, [])}'
if old not in s: raise SystemExit('Banner output line not found')
s = s.replace(old, new)
p.write_text(s)

# Transparent wordmarks are generated by the existing icon workflow.
p = Path('scripts/sync_game_icons.py')
s = p.read_text()
s = s.replace(
    '    "game_zzz.svg": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Zenless_Zone_Zero_wordmark.svg",\n',
    '    "game_zzz.svg": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Zenless_Zone_Zero_wordmark.svg",\n    "game_starrail.svg": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Honkai_Star_Rail_logo.svg",\n    "game_endfield.svg": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Arknights_Endfield_logo.svg",\n'
)
p.write_text(s)
