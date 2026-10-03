#!/usr/bin/env python3
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
OUT.mkdir(parents=True, exist_ok=True)

characters = {
    "genshin": ["vesna", "vodyanitsa", "escoffier", "skirk"],
    "wuthering-waves": ["hsin", "chisa", "iuno", "suoming", "lucilla", "lynae"],
    "zenless-zone-zero": ["roxy", "promeia"],
}

def download(url: str, target: Path) -> bool:
    try:
        req = Request(url, headers={"User-Agent": "G-Codus/1.0"})
        with urlopen(req, timeout=25) as r:
            data = r.read()
        if len(data) < 1024:
            return False
        target.write_bytes(data)
        return True
    except Exception:
        return False

for game, slugs in characters.items():
    for slug in slugs:
        filename = "banner_" + game.replace("-", "_") + "_" + slug + ".webp"
        target = OUT / filename
        urls = [
            f"https://cdn.prydwen.gg/images/{game}/characters/{slug}_full.webp",
            f"https://cdn.prydwen.gg/images/{game}/characters/{slug}.webp",
        ]
        if any(download(url, target) for url in urls):
            print("downloaded", game, slug)
        else:
            print("missing", game, slug)
            target.unlink(missing_ok=True)
