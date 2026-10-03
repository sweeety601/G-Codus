#!/usr/bin/env python3
import json
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
manifest_path = ROOT / "data/genshin_characters.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
out = ROOT / "assets/genshin/portraits"
out.mkdir(parents=True, exist_ok=True)

missing = []
downloaded = 0
entries = []


def download(key: str):
    url = "https://library.keqingmains.com/img/characters/round-icon/" + urllib.parse.quote(key) + ".png"
    filename = key.lower().replace(" ", "_") + ".png"
    dest = out / filename
    req = urllib.request.Request(url, headers={"User-Agent": "G-Codus-portrait-sync/1.0"})
    with urllib.request.urlopen(req, timeout=30) as response:
        data = response.read()
    if not data.startswith(b"\x89PNG"):
        raise ValueError("response is not PNG")
    dest.write_bytes(data)
    return str(dest.relative_to(ROOT)), url


for name in manifest["characters"]:
    key = "Aether" if name.startswith("Traveler ") else name
    try:
        local_path, url = download(key)
        entries.append({"name": name, "local_path": local_path, "portrait_url": url, "status": "downloaded"})
        downloaded += 1
    except Exception as exc:
        url = "https://library.keqingmains.com/img/characters/round-icon/" + urllib.parse.quote(key) + ".png"
        entries.append({"name": name, "local_path": None, "portrait_url": url, "status": "missing", "error": str(exc)})
        missing.append(name)

for key in ("Aether", "Lumine"):
    try:
        download(key)
    except Exception:
        pass

out_manifest = ROOT / "data/genshin_portrait_manifest.json"
out_manifest.write_text(
    json.dumps(
        {
            "game": "Genshin Impact",
            "source_character_list": manifest["source_url"],
            "portrait_source": "https://library.keqingmains.com/resources/tools/portraits",
            "last_synced": "2026-10-03",
            "character_count": len(entries),
            "downloaded": downloaded,
            "missing": missing,
            "characters": entries,
        },
        ensure_ascii=False,
        indent=2,
    )
    + "\n",
    encoding="utf-8",
)

print(f"downloaded={downloaded}; missing={len(missing)}")
if missing:
    print("Missing:", ", ".join(missing))
