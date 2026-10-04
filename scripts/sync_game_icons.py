# Final icon sync source: HSR uses the transparent wordmark with the game name.
#!/usr/bin/env python3
from pathlib import Path
from urllib.request import Request, urlopen
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "assets" / "game_icons" / "source"
DRAWABLE_DIR = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
SOURCE_DIR.mkdir(parents=True, exist_ok=True)
DRAWABLE_DIR.mkdir(parents=True, exist_ok=True)

ICONS = {
    "game_genshin.svg": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Genshin_Impact_wordmark.svg",
    "game_wuwa.svg": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Wuthering_Waves_logo.svg",
    "game_zzz.svg": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Zenless_Zone_Zero_wordmark.svg",
    "game_starrail.png": "https://images.seeklogo.com/logo-png/50/1/honkai-star-rail-logo-png_seeklogo-503999.png",
    "game_endfield.svg": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Arknights_Endfield_logo.svg",
}

for filename, url in ICONS.items():
    target = SOURCE_DIR / filename
    supplied = ROOT / "Honkai_Star_Rail_logo.png" if filename == "game_starrail.png" else None
    if supplied is not None and supplied.exists():
        target.write_bytes(supplied.read_bytes())
        print(f"Using supplied HSR logo: {supplied.name}")
        continue
    req = Request(url, headers={"User-Agent": "G-Codus/1.0"})
    with urlopen(req, timeout=30) as response:
        target.write_bytes(response.read())

subprocess.run(["python3", "-m", "pip", "install", "--quiet", "cairosvg", "pillow"], check=True)

from PIL import Image
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
    canvas.save(DRAWABLE_DIR / (filename.rsplit('.', 1)[0] + ".png"), "PNG", optimize=True)

print("Downloaded and integrated all five game icons with transparent backgrounds.")
