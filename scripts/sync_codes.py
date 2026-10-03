#!/usr/bin/env python3
import json
import urllib.request
from datetime import datetime, timezone

OUT = "app/src/main/assets/codes_feed.json"
SOURCES = {
    "genshin": [
        ("hoyo-codes", "https://hoyo-codes.seria.moe/codes?game=genshin"),
        ("OpenGachaCodes", "https://api.ennead.cc/codes/genshin"),
    ],
    "zzz": [
        ("hoyo-codes", "https://hoyo-codes.seria.moe/codes?game=nap"),
        ("OpenGachaCodes", "https://api.ennead.cc/codes/zenless"),
    ],
    "wuwa": [
        ("OpenGachaCodes", "https://api.ennead.cc/codes/wuwa"),
        ("game-codes", "https://game-codes.wisp.uno/codes/wuwa"),
        ("OpenGachaCodes fallback", "https://api.ennead.cc/codes/wutheringwaves"),
    ],
}


def fetch_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "G-Codus-Code-Sync/2.1", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def extract_items(data):
    if isinstance(data, list):
        return data
    if not isinstance(data, dict):
        return []
    for key in ("codes", "active", "data", "results"):
        value = data.get(key)
        if isinstance(value, list):
            return value
    return []


def normalize(game, item, source):
    if isinstance(item, str):
        item = {"code": item}
    if not isinstance(item, dict):
        return None
    code = str(item.get("code", "")).strip()
    if not code:
        return None

    rewards = item.get("rewards", item.get("reward", ""))
    if isinstance(rewards, list):
        rewards = ", ".join(map(str, rewards))
    elif isinstance(rewards, dict):
        rewards = ", ".join(f"{k}: {v}" for k, v in rewards.items())

    expires = item.get("expires_at") or item.get("expires") or item.get("expiry") or item.get("expiration") or ""
    return {
        "game": game,
        "code": code,
        "rewards": str(rewards or ""),
        "source": str(item.get("source") or source),
        "expires_at": str(expires or ""),
    }


def collect_game(game):
    collected = []
    errors = []
    for source, url in SOURCES[game]:
        try:
            data = fetch_json(url)
            items = extract_items(data)
            for item in items:
                value = normalize(game, item, source)
                if value:
                    collected.append(value)
        except Exception as exc:
            errors.append(f"{source}: {exc}")
    if not collected and errors:
        print(f"{game}: all sources failed: {' | '.join(errors)}")
    return collected


def collect():
    result = []
    for game in SOURCES:
        result.extend(collect_game(game))
    unique = {}
    for value in result:
        key = (value["game"], value["code"].upper())
        existing = unique.get(key)
        if existing is None:
            unique[key] = value
        else:
            if not existing.get("rewards") and value.get("rewards"):
                existing["rewards"] = value["rewards"]
            if not existing.get("expires_at") and value.get("expires_at"):
                existing["expires_at"] = value["expires_at"]
            if existing.get("source", "").startswith("OpenGachaCodes") and value.get("source") == "hoyo-codes":
                existing["source"] = value["source"]
    return list(unique.values())


def is_expired(item, now):
    value = item.get("expires_at", "")
    if not value:
        return False
    try:
        text = str(value).replace("Z", "+00:00")
        dt = datetime.fromisoformat(text)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt <= now
    except Exception:
        return False


def main():
    now = datetime.now(timezone.utc).replace(microsecond=0)
    now_text = now.isoformat().replace("+00:00", "Z")
    try:
        current = collect()
    except Exception as exc:
        print("Code collection failed:", exc)
        return

    try:
        with open(OUT, encoding="utf-8") as f:
            previous = json.load(f)
    except Exception:
        previous = {"active": [], "expired": []}

    previous_active = {(x.get("game"), x.get("code", "").upper()): x for x in previous.get("active", [])}
    previous_expired = {(x.get("game"), x.get("code", "").upper()): x for x in previous.get("expired", [])}

    active = []
    for item in current:
        key = (item["game"], item["code"].upper())
        if is_expired(item, now):
            previous_expired[key] = {**item, "expired_at": previous_expired.get(key, {}).get("expired_at", now_text)}
        else:
            active.append(item)
            previous_expired.pop(key, None)

    current_keys = {(x["game"], x["code"].upper()) for x in active}
    for key, item in previous_active.items():
        if key not in current_keys:
            previous_expired[key] = {
                "game": item.get("game"),
                "code": item.get("code"),
                "rewards": item.get("rewards", ""),
                "source": item.get("source", ""),
                "expires_at": item.get("expires_at", ""),
                "expired_at": previous_expired.get(key, {}).get("expired_at", now_text),
            }

    payload = {
        "generated_at": now_text,
        "active": sorted(active, key=lambda x: (x["game"], x["code"].upper())),
        "expired": sorted(previous_expired.values(), key=lambda x: x.get("expired_at", ""), reverse=True)[:500],
    }

    if not current and previous.get("active"):
        print("All sources returned no usable codes; keeping previous feed")
        return

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
