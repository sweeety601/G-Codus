#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/gcodus/MainActivity.kt"
ICON_SYNC = ROOT / "scripts/sync_game_icons.py"

s = MAIN.read_text()
repls = {
    'icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(122)))':
        'icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(136)))',
    'iconRow.addView(item, LinearLayout.LayoutParams(dp(94), dp(116)))':
        'iconRow.addView(item, LinearLayout.LayoutParams(dp(108), dp(130)))',
    'item.addView(iconFrame, LinearLayout.LayoutParams(dp(68), dp(68)))':
        'item.addView(iconFrame, LinearLayout.LayoutParams(dp(78), dp(78)))',
    'wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(126)))':
        'wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(140)))',
}
for old, new in repls.items():
    if old not in s:
        raise SystemExit(f"MainActivity replacement target not found: {old}")
    s = s.replace(old, new, 1)

old = '''    private fun sameCharacterIdentity(gameId: String, localName: String, onlineName: String, onlineSlug: String, localFile: String): Boolean {
        val a = normalizeCharacterForMatch(localName)
        val b = normalizeCharacterForMatch(onlineName)
        val slug = normalizeCharacterForMatch(onlineSlug)
        val file = normalizeCharacterForMatch(localFile.substringBeforeLast("."))
        if (gameId == "zzz") {'''
new = '''    private fun sameCharacterIdentity(gameId: String, localName: String, onlineName: String, onlineSlug: String, localFile: String): Boolean {
        val a = normalizeCharacterForMatch(localName)
        val b = normalizeCharacterForMatch(onlineName)
        val slug = normalizeCharacterForMatch(onlineSlug)
        val file = normalizeCharacterForMatch(localFile.substringBeforeLast("."))

        // HSR uses both "Mortenax Blade" in the local portrait database and
        // "Blade Mortenax" in banner/online data. They are the same identity.
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

        if (gameId == "zzz") {'''
if old not in s:
    raise SystemExit('sameCharacterIdentity target not found')
s = s.replace(old, new, 1)
MAIN.write_text(s)

# Make the uploaded root HSR logo authoritative. The old CDN source is used only
# when the user has not supplied Honkai_Star_Rail_logo.png in the repository root.
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
if old not in icon:
    raise SystemExit('icon sync replacement target not found')
ICON_SYNC.write_text(icon.replace(old, new, 1))
print('Applied portrait identity, top tile size, and supplied HSR logo fixes.')
