#!/usr/bin/env python3
import json
import urllib.request
from datetime import datetime, timezone

OUT = "app/src/main/assets/codes_feed.json"
SOURCES = {
    "genshin": "https://hoyo-codes.seria.moe/codes?game=genshin",
    "zzz": "https://hoyo-codes.seria.moe/codes?game=nap",
    "wuwa": "https://api.ennead.cc/codes/wuwa",
}

def fetch_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "G-Codus-Code-Sync/1.0"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read().decode())

def normalize(game, item, source):
    code = str(item.get("code", "")).strip()
    if not code:
        return None
    rewards = item.get("rewards", "")
    if isinstance(rewards, list):
        rewards = ", ".join(map(str, rewards))
    return {
        "game": game,
        "code": code,
        "rewards": str(rewards or ""),
        "source": source,
        "expires_at": item.get("expires_at") or item.get("expires") or "",
    }

def collect():
    result = []
    for game in ("genshin", "zzz"):
        data = fetch_json(SOURCES[game])
        items = data if isinstance(data, list) else data.get("codes", [])
        for item in items:
            item = item if isinstance(item, dict) else {"code": item}
            value = normalize(game, item, "hoyo-codes")
            if value:
                result.append(value)

    data = fetch_json(SOURCES["wuwa"])
    items = data if isinstance(data, list) else data.get("codes", data.get("active", []))
    for item in items:
        item = item if isinstance(item, dict) else {"code": item}
        value = normalize("wuwa", item, "OpenGachaCodes")
        if value:
            result.append(value)

    unique = {}
    for value in result:
        unique[(value["game"], value["code"].upper())] = value
    return list(unique.values())

def main():
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    try:
        current = collect()
    except Exception as exc:
        print("Sources unavailable:", exc)
        return

    try:
        with open(OUT, encoding="utf-8") as f:
            previous = json.load(f)
    except Exception:
        previous = {"active": [], "expired": []}

    old = {(x.get("game"), x.get("code", "").upper()): x for x in previous.get("active", [])}
    current_keys = {(x["game"], x["code"].upper()) for x in current}

    expired = list(previous.get("expired", []))
    for key, item in old.items():
        if key not in current_keys:
            expired.append({
                "game": item.get("game"),
                "code": item.get("code"),
                "rewards": item.get("rewards", ""),
                "source": item.get("source", ""),
                "expires_at": item.get("expires_at", ""),
                "expired_at": now
            })

    expired_map = {(x.get("game"), x.get("code", "").upper()): x for x in expired}
    payload = {
        "generated_at": now,
        "active": sorted(current, key=lambda x: (x["game"], x["code"].upper())),
        "expired": sorted(expired_map.values(), key=lambda x: x.get("expired_at", ""), reverse=True)[:500]
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")

if __name__ == "__main__":
    main()
