#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/gcodus/MainActivity.kt"
ICON_SYNC = ROOT / "scripts/sync_game_icons.py"

# Current fixes: local HSR/Endfield portraits, HSR name aliases, and larger top game tiles.
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
    if old in s:
        s = s.replace(old, new, 1)
    elif new not in s:
        raise SystemExit(f"MainActivity replacement target not found: {old}")

# The online banner source and local database use reversed HSR names:
# "Blade Mortenax" vs "Mortenax Blade". Treat them as one identity.
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
    raise SystemExit('HSR identity target not found')
s = s.replace(old, new, 1)

# Make local portrait lookup accept both HSR name orders.
old = '''            "billy-starlight", "starlight-billy", "starlight-billy-kid" ->
                listOf("billy-starlight", "starlight-billy", "starlight-billy-kid")
            "corin", "corin-wickes" ->'''
new = '''            "billy-starlight", "starlight-billy", "starlight-billy-kid" ->
                listOf("billy-starlight", "starlight-billy", "starlight-billy-kid")
            "mortenax-blade", "blade-mortenax" ->
                listOf("mortenax-blade", "blade-mortenax")
            "corin", "corin-wickes" ->'''
if old not in s:
    raise SystemExit('HSR portrait alias target not found')
s = s.replace(old, new, 1)
MAIN.write_text(s)

# The newly uploaded root HSR logo is authoritative.
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
print('Applied current HSR portrait matching, top tile sizing, and supplied logo fixes.')
