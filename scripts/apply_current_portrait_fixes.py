#!/usr/bin/env python3
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/gcodus/MainActivity.kt"
DB = ROOT / "app/src/main/java/com/example/gcodus/CharacterDatabase.kt"
ICON_SYNC = ROOT / "scripts/sync_game_icons.py"
MANIFEST = ROOT / "data/gacha_character_manifest.json"

s = MAIN.read_text()

# The top game tiles are deliberately larger than the old 94x116 layout.
s = s.replace('icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(122)))',
              'icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(136)))')
s = s.replace('iconRow.addView(item, LinearLayout.LayoutParams(dp(94), dp(116)))',
              'iconRow.addView(item, LinearLayout.LayoutParams(dp(108), dp(130)))')
s = s.replace('item.addView(iconFrame, LinearLayout.LayoutParams(dp(68), dp(68)))',
              'item.addView(iconFrame, LinearLayout.LayoutParams(dp(78), dp(78)))')
s = s.replace('wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(126)))',
              'wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(140)))')

# Blade Mortenax / Mortenax Blade must be one local identity.
pattern = re.compile(r'    private fun sameCharacterIdentity\(gameId: String, localName: String, onlineName: String, onlineSlug: String, localFile: String\): Boolean \{.*?\n    \}\n\n    private fun ', re.S)
replacement = '''    private fun sameCharacterIdentity(gameId: String, localName: String, onlineName: String, onlineSlug: String, localFile: String): Boolean {
        val a = normalizeCharacterForMatch(localName)
        val b = normalizeCharacterForMatch(onlineName)
        val slug = normalizeCharacterForMatch(onlineSlug)
        val file = normalizeCharacterForMatch(localFile.substringBeforeLast("."))

        if (gameId == "starrail") {
            fun hsrCanonical(value: String): String = when (value) {
                "mortenaxblade", "blademortenax" -> "mortenaxblade"
                else -> value
            }
            val localCanonical = hsrCanonical(a)
            val onlineCanonical = hsrCanonical(b)
            val localFileCanonical = hsrCanonical(file)
            val onlineSlugCanonical = hsrCanonical(slug)
            if (localCanonical == onlineCanonical || localFileCanonical == onlineSlugCanonical) return true
        }

        return when (gameId) {
            "zzz" -> {
                val local = normalizeCharacterForMatch(localName)
                val online = normalizeCharacterForMatch(onlineName)
                val localSlug = normalizeCharacterForMatch(localFile)
                local == online || localSlug == normalizeCharacterForMatch(onlineSlug)
            }
            else -> normalizeCharacterForMatch(localName) == normalizeCharacterForMatch(onlineName) ||
                normalizeCharacterForMatch(localFile) == normalizeCharacterForMatch(onlineSlug)
        }
    }

    private fun '''
if not pattern.search(s):
    raise SystemExit('sameCharacterIdentity function not found')
s = pattern.sub(replacement, s, count=1)

# Give the local uploaded portrait its canonical display name.
needle = '            "billy" to "Billy Kid"\n'
if needle in s and '"blade-mortenax" to "Mortenax Blade"' not in s:
    s = s.replace(needle, needle + '            "blade-mortenax" to "Mortenax Blade"\n', 1)

MAIN.write_text(s)

# Keep online HSR canonical keys stable too.
db = DB.read_text()
old = '''    private fun canonicalKey(gameId: String, slug: String, name: String): String {
        if (gameId == "zzz") {'''
new = '''    private fun canonicalKey(gameId: String, slug: String, name: String): String {
        if (gameId == "starrail") {
            val s = normalize(slug)
            val n = normalize(name)
            if (s == "blademortenax" || s == "mortenaxblade" || n == "blademortenax" || n == "mortenaxblade") return "starrail|mortenaxblade"
        }
        if (gameId == "zzz") {'''
if old in db:
    db = db.replace(old, new, 1)
DB.write_text(db)

# The supplied HSR logo is authoritative.
icon = ICON_SYNC.read_text()
old = '''for filename, url in ICONS.items():
    target = SOURCE_DIR / filename
    req = Request(url, headers={"User-Agent": "G-Codus/1.0"})
    with urlopen(req, timeout=30) as response:
        target.write_bytes(response.read())
'''
new = '''for filename, url in ICONS.items():
    target = SOURCE_DIR / filename
    supplied = ROOT / "Honkai_Star_Rail_logo.png" if filename == "game_starrail.png" else None
    if supplied is not None and supplied.exists():
        target.write_bytes(supplied.read_bytes())
        print(f"Using supplied HSR logo: {supplied.name}")
        continue
    req = Request(url, headers={"User-Agent": "G-Codus/1.0"})
    with urlopen(req, timeout=30) as response:
        target.write_bytes(response.read())
'''
if old in icon:
    ICON_SYNC.write_text(icon.replace(old, new, 1))

# Remove the wrongly duplicated HSR Perlica manifest entry and keep the Endfield one.
if MANIFEST.exists():
    data = json.loads(MANIFEST.read_text())
    images = data.get("images", [])
    images = [x for x in images if not (x.get("game") == "Honkai: Star Rail" and x.get("file") == "perlica_card.webp")]
    if not any(x.get("game") == "Arknights: Endfield" and x.get("file") == "perlica_card.webp" for x in images):
        images.append({"game": "Arknights: Endfield", "character": "Perlica", "file": "perlica_card.webp"})
    counts = {}
    for x in images:
        counts[x["game"]] = counts.get(x["game"], 0) + 1
    data["images"] = images
    data["total_images"] = len(images)
    data["games"] = counts
    MANIFEST.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")

print('Applied current portrait, HSR identity, top tile, logo, and Perlica fixes.')
