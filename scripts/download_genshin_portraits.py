#!/usr/bin/env python3
import html
import json
import re
import time
import urllib.request
from io import BytesIO
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
CHARACTERS_FILE = ROOT / "data/genshin_characters.json"
OUT = ROOT / "assets/genshin/portraits"
MANIFEST_OUT = ROOT / "data/genshin_portrait_manifest.json"
BASE = "https://www.prydwen.gg"
CDN = "https://cdn.prydwen.gg/images/genshin-impact/characters/"
INDEX_URL = f"{BASE}/genshin-impact/characters"
USER_AGENT = "G-Codus/1.0 (Genshin portrait asset sync)"


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=25) as response:
        return response.read()


def clean_text(value: str) -> str:
    value = re.sub(r"<[^>]+>", " ", value)
    value = html.unescape(value)
    return re.sub(r"\s+", " ", value).strip()


def norm(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())


def parse_character_links(page: str):
    links = {}
    pattern = re.compile(
        r'<a[^>]+href=["\'](/genshin-impact/characters/[^"\']+)["\'][^>]*>(.*?)</a>',
        re.I | re.S,
    )
    for href, body in pattern.findall(page):
        name = clean_text(body)
        if name:
            links.setdefault(norm(name), BASE + href)
    return links


def save_portrait(data: bytes, dest: Path):
    image = Image.open(BytesIO(data)).convert("RGBA")
    bbox = image.getbbox()
    if bbox:
        image = image.crop(bbox)
    image.thumbnail((720, 720), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (768, 768), (0, 0, 0, 0))
    x = (canvas.width - image.width) // 2
    y = (canvas.height - image.height) // 2
    canvas.alpha_composite(image, (x, y))
    canvas.save(dest, "PNG", optimize=True)


manifest = json.loads(CHARACTERS_FILE.read_text(encoding="utf-8"))
characters = manifest["characters"]
OUT.mkdir(parents=True, exist_ok=True)
index_html = fetch(INDEX_URL).decode("utf-8", errors="replace")
links = parse_character_links(index_html)
entries = []
missing = []

for index, name in enumerate(characters, start=1):
    page_url = links.get(norm(name))
    entry = {"name": name, "page_url": page_url, "portrait_url": None, "local_path": None}
    try:
        if not page_url:
            raise RuntimeError("character page was not found on Prydwen")
        slug = page_url.rstrip("/").split("/")[-1]
        portrait_url = f"{CDN}{slug}_full.webp"
        data = fetch(portrait_url)
        dest = OUT / f"{slug}.png"
        save_portrait(data, dest)
        entry.update({
            "portrait_url": portrait_url,
            "local_path": str(dest.relative_to(ROOT)),
            "status": "downloaded",
        })
    except Exception as exc:
        entry.update({"status": "missing", "error": str(exc)})
        missing.append(name)
    entries.append(entry)
    print(f"[{index}/{len(characters)}] {name}: {entry['status']}")

MANIFEST_OUT.write_text(
    json.dumps({
        "game": "Genshin Impact",
        "source_character_list": INDEX_URL,
        "portrait_source": "Prydwen CDN character full-art images, cropped locally into square transparent portraits",
        "last_synced": time.strftime("%Y-%m-%d"),
        "character_count": len(entries),
        "downloaded": sum(e["status"] == "downloaded" for e in entries),
        "missing": missing,
        "characters": entries,
    }, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)

print(f"Downloaded: {len(entries) - len(missing)} / {len(entries)}")
if missing:
    print("Missing:", ", ".join(missing))
    raise SystemExit(1)
