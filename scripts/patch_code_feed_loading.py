#!/usr/bin/env python3
from pathlib import Path

PATH = Path("app/src/main/java/com/example/gcodus/MainActivity.kt")
text = PATH.read_text(encoding="utf-8")

old = '''    private fun loadCachedCodes(): CodesFeed {
        val json = prefs.getString("codes_feed", null) ?: return CodesFeed.empty()
        return try { parseCodesFeed(json) } catch (_: Exception) { CodesFeed.empty() }
    }
'''

new = '''    private fun loadCachedCodes(): CodesFeed {
        // Prefer the last successfully downloaded feed. On a fresh install there
        // is no SharedPreferences entry yet, so immediately fall back to the
        // codes_feed.json bundled into the APK. This guarantees that the code
        // page is populated even before the first background HTTP refresh.
        val cached = prefs.getString("codes_feed", null)
        if (!cached.isNullOrBlank()) {
            try {
                return parseCodesFeed(cached)
            } catch (_: Exception) {
                // Continue to the bundled feed.
            }
        }

        return try {
            val bundled = assets.open("codes_feed.json").bufferedReader().use { it.readText() }
            parseCodesFeed(bundled)
        } catch (_: Exception) {
            CodesFeed.empty()
        }
    }
'''

if old in text:
    text = text.replace(old, new, 1)
else:
    marker = '    private fun parseCodesFeed(json: String): CodesFeed {'
    if 'assets.open("codes_feed.json")' not in text and marker in text:
        raise SystemExit("loadCachedCodes block not found")

old_filter = '''        val active = codesFeed.active.filter { it.game == gameId }
        val expired = codesFeed.expired.filter { it.game == gameId }
'''
new_filter = '''        fun sameGame(value: String): Boolean {
            val normalized = value.trim().lowercase()
            return when (gameId.lowercase()) {
                "genshin" -> normalized in setOf("genshin", "genshinimpact", "genshin impact")
                "wuwa" -> normalized in setOf("wuwa", "wutheringwaves", "wuthering waves", "wutheringwave")
                "zzz" -> normalized in setOf("zzz", "zenless", "zenlesszonezero", "zenless zone zero")
                else -> normalized == gameId.lowercase()
            }
        }
        val active = codesFeed.active.filter { sameGame(it.game) }
        val expired = codesFeed.expired.filter { sameGame(it.game) }
'''
if old_filter in text:
    text = text.replace(old_filter, new_filter, 1)

PATH.write_text(text, encoding="utf-8")
print("Code feed loading patch applied")
# Trigger note: this script is also a dependency of the hourly/push sync workflow.
