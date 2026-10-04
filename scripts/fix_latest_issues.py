from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
main = ROOT / "app/src/main/java/com/example/gcodus/MainActivity.kt"
s = main.read_text()

def replace_once(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f"{label}: target block not found")
    s = s.replace(old, new, 1)

# 1) The five game buttons must have enough vertical room for their labels.
# Previously the 94dp row clipped HSR/Endfield's second line.
s = s.replace('icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(100)))',
              'icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(122)))')
s = s.replace('iconRow.addView(item, LinearLayout.LayoutParams(dp(94), dp(94)))',
              'iconRow.addView(item, LinearLayout.LayoutParams(dp(94), dp(116)))')
s = s.replace('wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(104)))',
              'wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(126)))')

# 2) Local portrait lookup must cover all five games and ignore technical suffixes.
old = '''        val gameFolder = when (gameId) {
            "genshin" -> "genshin"
            "wuwa" -> "wuthering_waves"
            "zzz" -> "zenless_zone_zero"
            else -> return
        }'''
new = '''        val gameFolder = when (gameId) {
            "genshin" -> "genshin"
            "wuwa" -> "wuthering_waves"
            "zzz" -> "zenless_zone_zero"
            "starrail" -> "honkai_star_rail"
            "endfield" -> "arknights_endfield"
            else -> return
        }'''
replace_once(old, new, "portrait game folders")

old = '''                val stem = file.substringBeforeLast('.').lowercase()
                    .replace("’", "")
                    .replace("'", "")
                    .replace(":", "")
                    .replace("&", "and")
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')'''
new = '''                val stem = file.substringBeforeLast('.')
                    .removeSuffix("_card")
                    .removeSuffix("_full")
                    .lowercase()
                    .replace("’", "")
                    .replace("'", "")
                    .replace(":", "")
                    .replace("&", "and")
                    .replace("•", "-")
                    .replace("·", "-")
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')'''
replace_once(old, new, "portrait filename normalization")

# Character display names from local HSR assets should never expose technical suffixes or bullet separators.
old = '''        val base = file.substringBeforeLast(".")
            .removeSuffix("_card")
            .removeSuffix("_full")'''
new = '''        val base = file.substringBeforeLast(".")
            .removeSuffix("_card")
            .removeSuffix("_full")
            .replace("•", " ")
            .replace("·", " ")
            .replace(Regex("\\s+"), " ")
            .trim()'''
replace_once(old, new, "character display name cleanup")

# 3) Endfield Si is currently treated as a 6★ upcoming/leak entry in the character roster.
old = '''            n in setOf("arcane","ardelia","camille","ember","endministrator","gilberta","laevatain","lastrite","lifeng","liino","mifu","pogranichnik","rossi","tangtang","typhoeus","yvonne","zhuangfangyi") -> 6'''
new = '''            n in setOf("arcane","ardelia","camille","ember","endministrator","gilberta","laevatain","lastrite","lifeng","liino","mifu","pogranichnik","rossi","si","tangtang","typhoeus","yvonne","zhuangfangyi") -> 6'''
replace_once(old, new, "Endfield Si rarity")

# 4) Banner hero cards were hard-coded to 5★. Endfield character banners are 6★.
old = '''        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL'''
new = '''        val bannerRarity = if (gameId == "endfield") "6★" else "5★"

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL'''
replace_once(old, new, "banner rarity declaration")
s = s.replace('overlay.addView(label("5★", 28f, Color.WHITE, true))',
              'overlay.addView(label(bannerRarity, 28f, Color.WHITE, true))', 1)
s = s.replace('info.addView(label("5★", 17f, Color.rgb(255, 211, 76), true))',
              'info.addView(label(bannerRarity, 17f, Color.rgb(255, 211, 76), true))', 1)

# 5) Sanitize bullet separators in banner names too (Prydwen uses • in HSR variant names).
old = '''                val fiveStars = (0 until arr.length())
                .map { arr.optString(it) }'''
# Keep indentation-independent replacement below instead.
s = s.replace('''            val fiveStars = (0 until arr.length())
                .map { arr.optString(it) }
                .filter { it.isNotBlank() }
                .distinct()''',
'''            val fiveStars = (0 until arr.length())
                .map { arr.optString(it).replace("•", " ").replace("·", " ").replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
                .distinct()''', 1)

# Make remote fallback available for HSR/Endfield after local lookup fails.
# The existing loadPrydwenPortrait already knows both slugs; this change only makes
# the local-first path reach it for the two newly bundled games.

main.write_text(s)

# 6) Replace the HSR icon with a transparent wordmark that actually contains the game name.
icons = ROOT / "scripts/sync_game_icons.py"
i = icons.read_text()
i = i.replace('''    "game_starrail.svg": "https://brandlogos.sgp1.digitaloceanspaces.com/svg/arcticons/honkai-star-rail.svg",''',
              '''    "game_starrail.png": "https://images.seeklogo.com/logo-png/50/1/honkai-star-rail-logo-png_seeklogo-503999.png",''')
old = '''for filename in ICONS:
    src = SOURCE_DIR / filename
    rendered = DRAWABLE_DIR / (filename.replace(".svg", "_rendered.png"))
    out = DRAWABLE_DIR / filename.replace(".svg", ".png")

    subprocess.run(
        ["python3", "-c",
         "import cairosvg,sys; cairosvg.svg2png(url=sys.argv[1],write_to=sys.argv[2],output_width=220)",
         str(src), str(rendered)],
        check=True,
    )

    from PIL import Image
    logo = Image.open(rendered).convert("RGBA")
    canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    logo.thumbnail((220, 180), Image.Resampling.LANCZOS)
    canvas.alpha_composite(logo, ((256 - logo.width) // 2, (256 - logo.height) // 2))
    canvas.save(out, "PNG", optimize=True)
    rendered.unlink(missing_ok=True)'''
new = '''from PIL import Image
for filename in ICONS:
    src = SOURCE_DIR / filename
    if filename.endswith(".png"):
        out = DRAWABLE_DIR / filename
        logo = Image.open(src).convert("RGBA")
    else:
        rendered = DRAWABLE_DIR / (filename.replace(".svg", "_rendered.png"))
        out = DRAWABLE_DIR / filename.replace(".svg", ".png")
        subprocess.run(
            ["python3", "-c",
             "import cairosvg,sys; cairosvg.svg2png(url=sys.argv[1],write_to=sys.argv[2],output_width=220)",
             str(src), str(rendered)],
            check=True,
        )
        logo = Image.open(rendered).convert("RGBA")
        rendered.unlink(missing_ok=True)

    canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    logo.thumbnail((232, 190), Image.Resampling.LANCZOS)
    canvas.alpha_composite(logo, ((256 - logo.width) // 2, (256 - logo.height) // 2))
    canvas.save(DRAWABLE_DIR / (filename.rsplit('.', 1)[0] + ".png"), "PNG", optimize=True)'''
if old not in i:
    raise SystemExit("icon rendering block not found")
i = i.replace(old, new, 1)
icons.write_text(i)

# 7) Clean HSR bullet separators in the character database and keep fallback names consistent.
chars = ROOT / "app/src/main/java/com/example/gcodus/CharacterDatabase.kt"
c = chars.read_text()
c = c.replace('s = s.replace(Regex("\\\\s+"), " ").trim()',
              's = s.replace("•", " ").replace("·", " ").replace(Regex("\\\\s+"), " ").trim()')
chars.write_text(c)

print("Applied latest UI, portrait, rarity and HSR naming fixes")
