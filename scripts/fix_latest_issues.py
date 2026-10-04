# Trigger fix workflow.
"+""+"from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
main = ROOT / "app/src/main/java/com/example/gcodus/MainActivity.kt"
s = main.read_text()

def replace_once(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f"{label}: target block not found")
    s = s.replace(old, new, 1)

s = s.replace('icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(100)))','icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(122)))')
s = s.replace('iconRow.addView(item, LinearLayout.LayoutParams(dp(94), dp(94)))','iconRow.addView(item, LinearLayout.LayoutParams(dp(94), dp(116)))')
s = s.replace('wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(104)))','wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(126)))')
replace_once('''        val gameFolder = when (gameId) {
            "genshin" -> "genshin"
            "wuwa" -> "wuthering_waves"
            "zzz" -> "zenless_zone_zero"
            else -> return
        }''','''        val gameFolder = when (gameId) {
            "genshin" -> "genshin"
            "wuwa" -> "wuthering_waves"
            "zzz" -> "zenless_zone_zero"
            "starrail" -> "honkai_star_rail"
            "endfield" -> "arknights_endfield"
            else -> return
        }''','portrait game folders')
replace_once('''                val stem = file.substringBeforeLast('.').lowercase()
                    .replace("’", "")
                    .replace("'", "")
                    .replace(":", "")
                    .replace("&", "and")
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')''','''                val stem = file.substringBeforeLast('.')
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
                    .trim('-')''','portrait filename normalization')
replace_once('''        val base = file.substringBeforeLast(".")
            .removeSuffix("_card")
            .removeSuffix("_full")''','''        val base = file.substringBeforeLast(".")
            .removeSuffix("_card")
            .removeSuffix("_full")
            .replace("•", " ")
            .replace("·", " ")
            .replace(Regex("\\s+"), " ")
            .trim()''','character display name cleanup')
replace_once('''            n in setOf("arcane","ardelia","camille","ember","endministrator","gilberta","laevatain","lastrite","lifeng","liino","mifu","pogranichnik","rossi","tangtang","typhoeus","yvonne","zhuangfangyi") -> 6''','''            n in setOf("arcane","ardelia","camille","ember","endministrator","gilberta","laevatain","lastrite","lifeng","liino","mifu","pogranichnik","rossi","si","tangtang","typhoeus","yvonne","zhuangfangyi") -> 6''','Endfield Si rarity')
replace_once('''        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL''','''        val bannerRarity = if (gameId == "endfield") "6★" else "5★"

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL''','banner rarity declaration')
s = s.replace('overlay.addView(label("5★", 28f, Color.WHITE, true))','overlay.addView(label(bannerRarity, 28f, Color.WHITE, true))',1)
s = s.replace('info.addView(label("5★", 17f, Color.rgb(255, 211, 76), true))','info.addView(label(bannerRarity, 17f, Color.rgb(255, 211, 76), true))',1)
s = s.replace('''            val fiveStars = (0 until arr.length())
                .map { arr.optString(it) }
                .filter { it.isNotBlank() }
                .distinct()''','''            val fiveStars = (0 until arr.length())
                .map { arr.optString(it).replace("•", " ").replace("·", " ").replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
                .distinct()''',1)
main.write_text(s)

icons = ROOT / "scripts/sync_game_icons.py"
i = icons.read_text()
i = i.replace('''    "game_starrail.svg": "https://brandlogos.sgp1.digitaloceanspaces.com/svg/arcticons/honkai-star-rail.svg",''','''    "game_starrail.png": "https://images.seeklogo.com/logo-png/50/1/honkai-star-rail-logo-png_seeklogo-503999.png",''')
old='''for filename in ICONS:
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
new='''from PIL import Image
for filename in ICONS:
    src = SOURCE_DIR / filename
    if filename.endswith(".png"):
        logo = Image.open(src).convert("RGBA")
    else:
        rendered = DRAWABLE_DIR / (filename.replace(".svg", "_rendered.png"))
        subprocess.run(["python3", "-c", "import cairosvg,sys; cairosvg.svg2png(url=sys.argv[1],write_to=sys.argv[2],output_width=220)", str(src), str(rendered)], check=True)
        logo = Image.open(rendered).convert("RGBA")
        rendered.unlink(missing_ok=True)
    canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    logo.thumbnail((232, 190), Image.Resampling.LANCZOS)
    canvas.alpha_composite(logo, ((256 - logo.width) // 2, (256 - logo.height) // 2))
    canvas.save(DRAWABLE_DIR / (filename.rsplit('.', 1)[0] + ".png"), "PNG", optimize=True)'''
if old not in i: raise SystemExit('icon rendering block not found')
i=i.replace(old,new,1)
icons.write_text(i)

chars = ROOT / "app/src/main/java/com/example/gcodus/CharacterDatabase.kt"
c=chars.read_text()
c=c.replace('s = s.replace(Regex("\\\\s+"), " ").trim()','s = s.replace("•", " ").replace("·", " ").replace(Regex("\\\\s+"), " ").trim()')
chars.write_text(c)
print("Applied latest UI, portrait, rarity and HSR naming fixes")
