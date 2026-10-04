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
    "Honkai: Star Rail": "https://www.prydwen.gg/star-rail/banners",
    "Arknights: Endfield": "https://www.prydwen.gg/arknights-endfield/banners",
}

SECONDARY = {
    "Genshin Impact": ["https://timesaver.gg/blog/genshin-banner-schedule-october-2026"],
    "Wuthering Waves": ["https://allthings.how/wuthering-waves-3-7-banners-hsin-and-suoming-pull-priority/"],
    "Zenless Zone Zero": [
        "https://keygold.gg/blog/detail/zenless-zone-zero-3-3-phoenix-severian-leaks",
        "https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/",
    ],
    "Honkai: Star Rail": [
        "https://www.prydwen.gg/star-rail/banners",
        "https://hsr.hoyoverse.com/",
    ],
    "Arknights: Endfield": [
        "https://endfield.gryphline.com/en-us/news/3839",
        "https://www.prydwen.gg/arknights-endfield/banners",
    ],
}

UA = "G-Codus/2.1 banner-sync"


def http_get(url, timeout=30):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read().decode("utf-8", "replace")


def clean(value):
    value = re.sub(r"<[^>]+>", " ", value or "")
    return re.sub(r"\s+", " ", value).strip()


def parse_date_range(value):
    m = re.search(r"(?i)\b((?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+(\d{1,2}),\s*(\d{4}))\s*[–-]\s*((?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+(\d{1,2}),\s*(\d{4}))", value or "")
    if not m:
        return None
    def parse(v):
        for fmt in ("%B %d, %Y", "%b %d, %Y"):
            try: return datetime.strptime(v, fmt).date().isoformat()
            except ValueError: pass
        return None
    start, end = parse(m.group(1)), parse(m.group(4))
    return {"start_date": start, "end_date": end} if start and end else None


def phase_from_text(text):
    m = re.search(r"(?i)(?:patch|version|v)?\s*(\d+\.\d+)\s*phase\s*([12])", text or "")
    if m: return f"{m.group(1)} Phase {m.group(2)}"
    m = re.search(r"(?i)(?:patch|version)\s*(\d+\.\d+)", text or "")
    return m.group(1) if m else ""


def classify_character(href, link, cache):
    context = clean(link.get("context", "")).lower()
    if re.search(r"5\s*[★⭐]|5[- ]star|s[- ]rank", context): return 5
    if re.search(r"4\s*[★⭐]|4[- ]star|a[- ]rank", context): return 4
    if href in cache: return cache[href]
    try:
        body = clean(http_get(href, timeout=20)).lower()
        if re.search(r"5\s*[★⭐]|5[- ]star", body): cache[href] = 5; return 5
        if re.search(r"4\s*[★⭐]|4[- ]star", body): cache[href] = 4; return 4
    except Exception: pass
    cache[href] = 0
    return 0


def normalize_cards(cards, primary_url):
    result, rarity_cache = [], {}
    for card in cards:
        text, section = clean(card.get("text", "")), clean(card.get("section", ""))
        links = card.get("character_links") or []
        names, four_stars = [], []
        for link in links:
            href = str(link.get("href", ""))
            if not re.search(r"/characters/[^/?#]+", href, re.I): continue
            name = clean(link.get("name", "")) or clean(link.get("alt", ""))
            if not name: name = href.rstrip("/").split("/")[-1].replace("-", " ")
            if not name: continue
            # Do not open every character page just to determine rarity.
            # That turns a single banner-page scrape into dozens of slow requests.
            # The banner card context is sufficient when the source exposes rarity;
            # otherwise the character is kept in the main list and known schedules
            # provide exact 4-star lineups for supported confirmed phases.
            context_lower = clean(link.get("context", "")).lower()
            if re.search(r"4\s*[★⭐]|4[- ]star|a[- ]rank", context_lower):
                rarity = 4
            elif re.search(r"5\s*[★⭐]|5[- ]star|s[- ]rank", context_lower):
                rarity = 5
            else:
                rarity = 0
            target = four_stars if rarity == 4 else names
            if name.lower() not in {x.lower() for x in target}: target.append(name)
        if not names and not four_stars: continue
        sl = section.lower()
        if "current character" in sl: bucket = "current"
        elif "next character" in sl: bucket = "next"
        elif "upcoming" in sl or "teased" in sl: bucket = "upcoming"
        else: continue
        item = {
            "phase": phase_from_text(text) or phase_from_text(section),
            "type": "character", "characters": names, "four_star": four_stars,
            "status": "live" if bucket == "current" else ("next" if bucket == "next" else "upcoming"),
            "source_status": "confirmed", "official_source": primary_url,
        }
        date_range = parse_date_range(text)
        if date_range: item.update(date_range)
        result.append((bucket, item))
    return result


def scrape_with_playwright(url):
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1400})
        page.goto(url, wait_until="domcontentloaded", timeout=30000)
        page.wait_for_timeout(1500)
        cards = page.evaluate("""() => {
            const headings=[...document.querySelectorAll('h1,h2,h3,h4')];
            const nodes=[...document.querySelectorAll('.custom-banner-header')];
            return nodes.map(card=>{
                const previous=headings.filter(h=>(h.compareDocumentPosition(card)&Node.DOCUMENT_POSITION_FOLLOWING)!==0);
                const heading=previous.length?previous[previous.length-1]:null;
                return {text:(card.innerText||'').trim(),section:heading?(heading.innerText||'').trim():'',character_links:[...card.querySelectorAll('a[href*="/characters/"]')].map(a=>{
                    const img=a.querySelector('img'); let node=a; const context=[];
                    for(let i=0;i<5&&node;i++,node=node.parentElement){context.push((node.innerText||'').trim());context.push((node.className||'').toString());}
                    return {href:a.href||'',name:(a.innerText||a.getAttribute('aria-label')||a.getAttribute('title')||'').trim(),alt:img?(img.alt||'').trim():'',context:context.join(' ')};
                })};
            });
        }""")
        browser.close()
        return cards


def dedupe(items):
    seen, result = set(), []
    for item in items:
        key=(item.get("phase",""),tuple(x.lower() for x in item.get("characters",[])),item.get("start_date",""),item.get("end_date",""))
        if key not in seen: seen.add(key); result.append(item)
    return result


KNOWN_SCHEDULES = {
    "Genshin Impact": [
        {
            "phase": "7.1 Phase 1", "type": "character",
            "characters": ["Vesna", "Vodyanitsa"],
            "start_date": "2026-09-23", "end_date": "2026-10-13",
            "status": "live", "source_status": "confirmed",
            "confirmation_basis": "Version 7.1 official notice",
            "official_source": "https://traveler.gg/version-7-1-event-wishes-notice-phase-i/",
            "secondary_source": SECONDARY["Genshin Impact"][0],
            "four_star": ["Diona", "Faruzan", "Chongyun"],
        },
        {
            "phase": "7.1 Phase 2", "type": "character",
            "characters": ["Escoffier", "Skirk"],
            "start_date": "2026-10-13", "end_date": "2026-11-03",
            "status": "upcoming", "source_status": "confirmed",
            "confirmation_basis": "Version 7.1 Special Program",
            "secondary_source": SECONDARY["Genshin Impact"][0],
            "four_star": ["Diona", "Faruzan", "Chongyun"],
        },
    ],
    "Wuthering Waves": [
        {
            "phase": "3.7 Phase 1", "type": "character",
            "characters": ["Hsin", "Chisa", "Iuno"],
            "start_date": "2026-09-30", "end_date": "2026-10-22",
            "status": "live", "source_status": "confirmed",
            "confirmation_basis": "Version 3.7 official notice",
            "official_source": "https://wutheringwaves.kurogames.com/zh-tw/main/news/detail/5528",
            "secondary_source": SECONDARY["Wuthering Waves"][0],
            "four_star": ["Buling", "Taoqi", "Youhu"],
        },
        {
            "phase": "3.7 Phase 2", "type": "character",
            "characters": ["Suoming", "Lucilla", "Lynae"],
            "start_date": "2026-10-22", "end_date": "2026-11-11",
            "status": "upcoming", "source_status": "confirmed",
            "confirmation_basis": "Version 3.7 official schedule / Special Report",
            "secondary_source": SECONDARY["Wuthering Waves"][0],
            "four_star": ["Lumi", "Danjin", "Chixia"],
        },
    ],
    "Honkai: Star Rail": [
        {
            "phase": "4.6 Phase 1", "type": "character",
            "characters": ["Pearl", "Evanescia"],
            "start_date": "2026-09-28", "end_date": "2026-10-21",
            "status": "live", "source_status": "confirmed",
            "confirmation_basis": "Version 4.6 official announcement",
            "official_source": "https://hsr.hoyoverse.com/",
            "secondary_source": SECONDARY["Honkai: Star Rail"][0],
            "four_star": ["Qingque", "Xueyi", "Misha"],
        },
        {
            "phase": "4.6 Phase 2", "type": "character",
            "characters": ["Pearl", "Mortenax Blade"],
            "start_date": "2026-10-21", "end_date": "2026-11-10",
            "status": "upcoming", "source_status": "confirmed",
            "confirmation_basis": "Version 4.6 official announcement",
            "official_source": "https://hsr.hoyoverse.com/",
            "secondary_source": SECONDARY["Honkai: Star Rail"][0],
            "four_star": ["Qingque", "Xueyi", "Misha"],
        },
    ],
    "Arknights: Endfield": [
        {
            "phase": "1.5 Phase 2", "type": "character",
            "characters": ["Yvonne"],
            "start_date": "2026-09-24", "end_date": "2026-10-21",
            "status": "live", "source_status": "confirmed",
            "confirmation_basis": "Official RE-Factor Headhunting #1 announcement",
            "official_source": "https://endfield.gryphline.com/en-us/news/3839",
            "secondary_source": SECONDARY["Arknights: Endfield"][0],
            "four_star": [],
        },
    ],
}


def known_current(game):
    today = datetime.now(timezone.utc).date()
    phases = KNOWN_SCHEDULES.get(game, [])
    for item in phases:
        if item["start_date"] <= today.isoformat() < item["end_date"]:
            result = dict(item)
            result["status"] = "live"
            result.pop("unconfirmed", None)
            return result
    return None


def known_next(game):
    today = datetime.now(timezone.utc).date()
    phases = KNOWN_SCHEDULES.get(game, [])
    future = [x for x in phases if x["start_date"] > today.isoformat()]
    if not future:
        return None
    result = dict(sorted(future, key=lambda x: x["start_date"])[0])
    result["status"] = "upcoming"
    result.pop("unconfirmed", None)
    return result


def secondary_zzz():
    for url in SECONDARY["Zenless Zone Zero"]:
        try: text=clean(http_get(url)).lower()
        except Exception: continue
        if "phoenix" in text and "severian" in text:
            return [
                {"phase":"3.3 Phase 1","type":"character","characters":["Phoenix"],"start_date":"2026-10-21","end_date":"2026-11-11","status":"upcoming","unconfirmed":True,"source_status":"unconfirmed","secondary_source":url},
                {"phase":"3.3 Phase 2","type":"character","characters":["Severian"],"start_date":"2026-11-11","end_date":"2026-12-02","status":"upcoming","unconfirmed":True,"source_status":"unconfirmed","secondary_source":url},
            ]
    return []


def main():
    previous={}
    if OUT.exists():
        try: previous=json.loads(OUT.read_text(encoding="utf-8"))
        except Exception: pass
    output={"version":3,"generated_at":datetime.now(timezone.utc).isoformat(),"source":"Prydwen + secondary fallback","games":{}}
    for game,url in PRIMARY.items():
        cards=[]
        try: cards=scrape_with_playwright(url)
        except Exception as exc: print(f"{game}: primary scrape failed: {exc}")
        grouped=normalize_cards(cards,url)
        current=dedupe([x[1] for x in grouped if x[0]=="current"])
        next_phase=dedupe([x[1] for x in grouped if x[0]=="next"])
        upcoming=dedupe([x[1] for x in grouped if x[0]=="upcoming"])
        old_game=previous.get("games",{}).get(game,{})
        if not current and old_game.get("current"):
            current=old_game["current"]

        # Use confirmed schedule knowledge as a safety net for games whose
        # primary tracker can lag behind an official phase change.
        forced_current = known_current(game)
        if forced_current:
            current=[forced_current]

        if not next_phase:
            known=known_next(game)
            if known:
                next_phase=[known]
            elif game=="Zenless Zone Zero":
                secondary=secondary_zzz()
                next_phase=[x for x in secondary if x["phase"]=="3.3 Phase 1"]
                upcoming.extend(x for x in secondary if x["phase"]!="3.3 Phase 1")

        known=known_next(game)
        if known and (not next_phase or any(x.get("source_status")!="confirmed" for x in next_phase)):
            next_phase=[known]

        # The app displays only the current and immediate next phase.
        upcoming=[]

        output["games"][game]={"source_url":url,"fetched_at":datetime.now(timezone.utc).isoformat(),"status":"source_reachable" if cards else "source_unavailable","current":current[:6],"next":next_phase[:6],"upcoming":[]}
    OUT.write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("Online banner sync completed:",OUT)

if __name__=="__main__": main()
