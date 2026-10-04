#!/usr/bin/env python3
import json
import re
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data/banner_feed.json"

PRIMARY = {
    "Genshin Impact": "https://www.prydwen.gg/genshin-impact/banners",
    "Wuthering Waves": "https://www.prydwen.gg/wuthering-waves/banners",
    "Zenless Zone Zero": "https://www.prydwen.gg/zenless/banners",
}

SECONDARY = {
    "Genshin Impact": [
        "https://timesaver.gg/blog/genshin-banner-schedule-october-2026",
    ],
    "Wuthering Waves": [
        "https://allthings.how/wuthering-waves-3-7-banners-hsin-and-suoming-pull-priority/",
    ],
    "Zenless Zone Zero": [
        "https://keygold.gg/blog/detail/zenless-zone-zero-3-3-phoenix-severian-leaks",
        "https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/",
    ],
}

UA = "G-Codus/2.1 banner-sync"


def http_get(url, timeout=30):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read().decode("utf-8", "replace")


def clean(value):
    value = re.sub(r"<[^>]+>", " ", value or "")
    return re.sub(r"\s+", " ", value).strip()


def parse_date_range(value):
    m = re.search(
        r"(?i)\b("
        r"Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
        r"Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|"
        r"Nov(?:ember)?|Dec(?:ember)?)\s+(\d{1,2}),\s*(\d{4})"
        r"\s*[–-]\s*"
        r"("
        r"Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
        r"Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|"
        r"Nov(?:ember)?|Dec(?:ember)?)\s+(\d{1,2}),\s*(\d{4})",
        value or "",
    )
    if not m:
        return None

    def parse(month, day, year):
        for fmt in ("%B %d %Y", "%b %d %Y"):
            try:
                return datetime.strptime(f"{month} {day} {year}", fmt).date().isoformat()
            except ValueError:
                pass
        return None

    start = parse(m.group(1), m.group(2), m.group(3))
    end = parse(m.group(4), m.group(5), m.group(6))
    return {"start_date": start, "end_date": end} if start and end else None


def phase_from_text(text):
    m = re.search(r"(?i)(?:patch|version|v)?\s*(\d+\.\d+)\s*phase\s*([12])", text or "")
    if m:
        return f"{m.group(1)} Phase {m.group(2)}"

    m = re.search(r"(?i)(?:patch|version)\s*(\d+\.\d+)", text or "")
    return m.group(1) if m else ""


def classify_character(href, link, cache):
    context = clean(link.get("context", "")).lower()
    if re.search(r"5\s*[★⭐]|5[- ]star|s[- ]rank", context):
        return 5
    if re.search(r"4\s*[★⭐]|4[- ]star|a[- ]rank", context):
        return 4
    if href in cache:
        return cache[href]
    try:
        body = clean(http_get(href, timeout=20)).lower()
        if re.search(r"5\s*[★⭐]|5[- ]star", body):
            cache[href] = 5
            return 5
        if re.search(r"4\s*[★⭐]|4[- ]star", body):
            cache[href] = 4
            return 4
    except Exception:
        pass
    cache[href] = 0
    return 0


def normalize_cards(cards, primary_url):
    result = []
    rarity_cache = {}
    for card in cards:
        text = clean(card.get("text", ""))
        section = clean(card.get("section", ""))
        links = card.get("character_links") or []

        names = []
        four_stars = []
        for link in links:
            href = str(link.get("href", ""))
            if not re.search(r"/characters/[^/?#]+", href, re.I):
                continue
            name = clean(link.get("name", "")) or clean(link.get("alt", ""))
            if not name:
                name = href.rstrip("/").split("/")[-1].replace("-", " ")
            if not name:
                continue
            rarity = classify_character(href, link, rarity_cache)
            target = four_stars if rarity == 4 else names
            if name.lower() not in {x.lower() for x in target}:
                target.append(name)

        if not names and not four_stars:
            continue

        section_lower = section.lower()
        if "current character" in section_lower:
            bucket = "current"
        elif "next character" in section_lower:
            bucket = "next"
        elif "upcoming" in section_lower or "teased" in section_lower:
            bucket = "upcoming"
        else:
            continue

        item = {
            "phase": phase_from_text(text) or phase_from_text(section),
            "type": "character",
            "characters": names,
            "four_star": four_stars,
            "status": "live" if bucket == "current" else ("next" if bucket == "next" else "upcoming"),
            "source_status": "confirmed",
            "official_source": primary_url,
        }
        date_range = parse_date_range(text)
        if date_range:
            item.update(date_range)
        result.append((bucket, item))
    return result

def scrape_with_playwright(url):
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1400})
        page.goto(url, wait_until="networkidle", timeout=90000)
        page.wait_for_timeout(3000)

        cards = page.evaluate(
            """() => {
                const headings = [...document.querySelectorAll('h1,h2,h3,h4')];
                const nodes = [...document.querySelectorAll('.custom-banner-header')];
                return nodes.map(card => {
                    const previous = headings.filter(h =>
                        (h.compareDocumentPosition(card) &
                         Node.DOCUMENT_POSITION_FOLLOWING) !== 0
                    );
                    const heading = previous.length ? previous[previous.length - 1] : null;
                    return {
                        text: (card.innerText || '').trim(),
                        section: heading ? (heading.innerText || '').trim() : '',
                        character_links: [...card.querySelectorAll('a[href*="/characters/"]')].map(a => {
                            const img = a.querySelector('img');
                            let node = a;
                            const context = [];
                            for (let i = 0; i < 5 && node; i++, node = node.parentElement) {
                                context.push((node.innerText || '').trim());
                                context.push((node.className || '').toString());
                            }
                            return {
                                href: a.href || '',
                                name: (a.innerText || a.getAttribute('aria-label') ||
                                       a.getAttribute('title') || '').trim(),
                                alt: img ? (img.alt || '').trim() : '',
                                context: context.join(' ')
                            };
                        })
                    };
                });
            }"""
        )
        browser.close()
        return cards


def dedupe(items):
    seen = set()
    result = []
    for item in items:
        key = (
            item.get("phase", ""),
            tuple(x.lower() for x in item.get("characters", [])),
            item.get("start_date", ""),
            item.get("end_date", ""),
        )
        if key not in seen:
            seen.add(key)
            result.append(item)
    return result


def secondary_zzz():
    # The current leak sources identify both 3.3 S-ranks. We retain both phase
    # candidates separately so the app never drops Severian just because Phoenix
    # is the immediate next phase.
    for url in SECONDARY["Zenless Zone Zero"]:
        try:
            text = clean(http_get(url)).lower()
        except Exception:
            continue
        if "phoenix" not in text or "severian" not in text:
            continue
        return [
            {
                "phase": "3.3 Phase 1",
                "type": "character",
                "characters": ["Phoenix"],
                "start_date": "2026-10-21",
                "end_date": "2026-11-11",
                "status": "upcoming",
                "unconfirmed": True,
                "source_status": "unconfirmed",
                "secondary_source": url,
            },
            {
                "phase": "3.3 Phase 2",
                "type": "character",
                "characters": ["Severian"],
                "start_date": "2026-11-11",
                "end_date": "2026-12-02",
                "status": "upcoming",
                "unconfirmed": True,
                "source_status": "unconfirmed",
                "secondary_source": url,
            },
        ]
    return []


def secondary_generic(game):
    for url in SECONDARY.get(game, []):
        try:
            text = clean(http_get(url)).lower()
        except Exception:
            continue

        if game == "Genshin Impact" and "skirk" in text and "escoffier" in text:
            return [{
                "phase": "7.1 Phase 2",
                "type": "character",
                "characters": ["Escoffier", "Skirk"],
                "start_date": "2026-10-13",
                "end_date": "2026-11-03",
                "status": "upcoming",
                "unconfirmed": True,
                "source_status": "unconfirmed",
                "secondary_source": url,
            }]

        if game == "Wuthering Waves" and all(x in text for x in ("suoming", "lucilla", "lynae")):
            return [{
                "phase": "3.7 Phase 2",
                "type": "character",
                "characters": ["Suoming", "Lucilla", "Lynae"],
                "start_date": "2026-10-22",
                "end_date": "2026-11-11",
                "status": "upcoming",
                "unconfirmed": True,
                "source_status": "unconfirmed",
                "secondary_source": url,
                "four_star": ["Lumi", "Danjin", "Chixia"],
            }]
    return []


def main():
    previous = {}
    if OUT.exists():
        try:
            previous = json.loads(OUT.read_text(encoding="utf-8"))
        except Exception:
            pass

    output = {
        "version": 3,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": "Prydwen + secondary fallback",
        "games": {},
    }

    for game, url in PRIMARY.items():
        cards = []
        try:
            cards = scrape_with_playwright(url)
        except Exception as exc:
            print(f"{game}: primary scrape failed: {exc}")

        grouped = normalize_cards(cards, url)
        current = dedupe([x[1] for x in grouped if x[0] == "current"])
        next_phase = dedupe([x[1] for x in grouped if x[0] == "next"])
        upcoming = dedupe([x[1] for x in grouped if x[0] == "upcoming"])

        old_game = previous.get("games", {}).get(game, {})
        if not current and old_game.get("current"):
            current = old_game["current"]

        if not next_phase:
            if game == "Zenless Zone Zero":
                secondary = secondary_zzz()
                next_phase = [x for x in secondary if x["phase"] == "3.3 Phase 1"]
                upcoming.extend(x for x in secondary if x["phase"] != "3.3 Phase 1")
            else:
                secondary = secondary_generic(game)
                if secondary:
                    next_phase = secondary

        # Do not let a stale previous leak survive once the primary source has
        # produced a real confirmed next phase.
        if next_phase and all(not x.get("unconfirmed", False) for x in next_phase):
            upcoming = []

        output["games"][game] = {
            "source_url": url,
            "fetched_at": datetime.now(timezone.utc).isoformat(),
            "status": "source_reachable" if cards else "source_unavailable",
            "current": current[:6],
            "next": next_phase[:6],
            "upcoming": dedupe(upcoming)[:12],
        }

        print(
            game,
            "current=", [x.get("characters", []) for x in current[:6]],
            "next=", [x.get("characters", []) for x in next_phase[:6]],
            "upcoming=", [x.get("characters", []) for x in upcoming[:12]],
        )

    OUT.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Online banner sync completed:", OUT)


if __name__ == "__main__":
    main()
