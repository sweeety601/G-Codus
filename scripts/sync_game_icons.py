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
}

for filename, url in ICONS.items():
    target = SOURCE_DIR / filename
    req = Request(url, headers={"User-Agent": "G-Codus/1.0"})
    with urlopen(req, timeout=30) as response:
        target.write_bytes(response.read())

try:
    subprocess.run(["python3", "-m", "pip", "install", "--quiet", "cairosvg"], check=True)
    for filename in ICONS:
        src = SOURCE_DIR / filename
        out = DRAWABLE_DIR / filename.replace(".svg", ".png")
        subprocess.run(
            ["python3", "-c",
             "import cairosvg,sys; cairosvg.svg2png(url=sys.argv[1],write_to=sys.argv[2],output_width=256,output_height=256)",
             str(src), str(out)],
            check=True,
        )
except Exception as exc:
    print("Icon conversion failed:", exc)
    raise

print("Downloaded and converted 3 game icons.")
