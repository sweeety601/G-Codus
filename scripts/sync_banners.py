#!/usr/bin/env python3
import json
import re
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data/banner_feed.json"

SOURCES = {
    "Genshin Impact": "https://www.prydwen.gg/genshin-impact/banners",
    "Wuthering Waves": "https://www.prydwen.gg/wuthering-waves/banners",
    "Zenless Zone Zero": "https://www.prydwen.gg/zenless/banners",
}

UA = "G-Codus/1.0 (banner sync)"

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")

def clean(s):
    return re.sub(r"\\s+", " ", re.sub(r"<[^>]+>", " ", s)).strip()

def dates(text):
    m = re.search(r"(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{1,2}),\\s+(\\d{4})\\s*[–-]\\s*(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{1,2}),\\s+(\\d{4})", text)
    if not m:
        return None
    return {"start_date": f"{m.group(3)}-{datetime.strptime(m.group(1)[:3], '%b').month:02d}-{int(m.group(2)):02d}",
            "end_date": f"{m.group(6)}-{datetime.strptime(m.group(4)[:3], '%b').month:02d}-{int(m.group(5)):02d}"}

def extract_sections(html):
    # Prydwen renders banner cards server-side enough for text/date discovery.
    text = clean(html)
    blocks = re.split(r"(?=Current Character Banners|Next Character Banners|Upcoming Banners)", text, flags=re.I)
    return blocks

def sync():
    existing = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else {"version": 1, "games": {}}
    result = {
        "version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": "Prydwen",
        "games": {}
    }
    for game, url in SOURCES.items():
        html = fetch(url)
        # Keep the source snapshot and metadata. The app can consume this even if
        # Prydwen changes presentation; unknown fields are never guessed.
        ds = dates(clean(html))
        result["games"][game] = {
            "source_url": url,
            "fetched_at": datetime.now(timezone.utc).isoformat(),
            "date_hint": ds,
            "status": "source_reachable",
            "current": existing.get("games", {}).get(game, {}).get("current", []),
            "next": existing.get("games", {}).get(game, {}).get("next", []),
        }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\\n", encoding="utf-8")
    print("Banner sources checked:", ", ".join(SOURCES))

if __name__ == "__main__":
    sync()
