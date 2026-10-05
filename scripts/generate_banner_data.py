from pathlib import Path
import json
import re
from datetime import datetime, timezone
from openpyxl import Workbook, load_workbook

ROOT = Path(__file__).resolve().parents[1]
BANNERS = ROOT / "banners"
OUT = ROOT / "data" / "banner_feed.json"

GAMES = {
    "wuwa": ("Wuthering Waves", "01_Wuthering_Waves"),
    "genshin": ("Genshin Impact", "02_Genshin_Impact"),
    "starrail": ("Honkai: Star Rail", "03_Honkai_Star_Rail"),
    "endfield": ("Arknights: Endfield", "04_Arknights_Endfield"),
    "zzz": ("Zenless Zone Zero", "05_Zenless_Zone_Zero"),
}
HEADERS = ["Версия и фаза", "Дата начала", "Дата окончания", "Персонажи в составе баннера", "4* в баннере"]
PHASE_RE = re.compile(r"^\d+\.\d+ Phase \d+$", re.IGNORECASE)
ID_RE = re.compile(r"^[1-5]\.\d+$")


def split_ids(value):
    if value is None:
        return []
    return [x.strip() for x in re.split(r"[,;\\n]+", str(value)) if x.strip()]


def make_initial_workbook(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    wb = Workbook()
    ws = wb.active
    ws.title = "Banners"
    ws.append(HEADERS)
    ws.freeze_panes = "A2"
    for i, width in enumerate([24, 16, 16, 42, 36], 1):
        ws.column_dimensions[chr(64 + i)].width = width
    wb.save(path)


def read_table(path, game_prefix):
    wb = load_workbook(path, read_only=True, data_only=True)
    ws = wb.active
    headers = [str(c.value or "").strip().lower() for c in ws[1]]
    missing = [h for h in HEADERS if h.lower() not in headers]
    if missing:
        raise SystemExit(f"{path}: missing columns: {missing}")
    indexes = {h.lower(): headers.index(h.lower()) for h in HEADERS}
    rows = []
    for row in ws.iter_rows(min_row=2, values_only=True):
        phase = str(row[indexes[HEADERS[0].lower()]] or "").strip()
        if not phase:
            continue
        if not PHASE_RE.fullmatch(phase):
            raise SystemExit(f"{path}: invalid version/phase '{phase}'")
        start = normalize_date(row[indexes[HEADERS[1].lower()]])
        end = normalize_date(row[indexes[HEADERS[2].lower()]])
        characters = split_ids(row[indexes[HEADERS[3].lower()]])
        four = split_ids(row[indexes[HEADERS[4].lower()]])
        for cid in characters + four:
            if not ID_RE.fullmatch(cid) or not cid.startswith(game_prefix + "."):
                raise SystemExit(f"{path}: invalid character id '{cid}', expected {game_prefix}.N")
        if not start or not end:
            raise SystemExit(f"{path}: {phase} requires start and end dates")
        rows.append({
            "phase": phase,
            "start": start,
            "end": end,
            "characters": characters,
            "four_star": four,
        })
    return rows


def normalize_date(value):
    if value is None or str(value).strip() == "":
        return ""
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    text = str(value).strip()
    formats = (
        "%Y-%m-%d", "%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M:%SZ",
        "%d.%m.%Y", "%d.%m.%Y %H:%M", "%d.%m.%Y %H:%M:%S",
        "%d/%m/%Y", "%d/%m/%Y %H:%M"
    )
    for fmt in formats:
        try:
            dt = datetime.strptime(text, fmt).replace(tzinfo=timezone.utc)
            return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
        except ValueError:
            pass
    raise SystemExit(f"Invalid date: {text}")


def parse_date(value):
    value = value.strip()
    for fmt in ("%Y-%m-%d", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M:%SZ"):
        try:
            dt = datetime.strptime(value, fmt)
            return dt.replace(tzinfo=timezone.utc)
        except ValueError:
            pass
    raise SystemExit(f"Invalid date: {value}")


def normalize(row, confirmed):
    return {**row, "source_status": "confirmed" if confirmed else "unconfirmed", "unconfirmed": not confirmed}


def build_game(game_id, game_name, prefix):
    _, base = GAMES[game_id]
    confirmed_path = BANNERS / f"{base}_confirmed.xlsx"
    leaks_path = BANNERS / f"{base}_leaks.xlsx"
    if not confirmed_path.exists():
        make_initial_workbook(confirmed_path)
    if not leaks_path.exists():
        make_initial_workbook(leaks_path)

    confirmed = {r["phase"].lower(): normalize(r, True) for r in read_table(confirmed_path, prefix)}
    leaks = {r["phase"].lower(): normalize(r, False) for r in read_table(leaks_path, prefix)}
    merged = dict(leaks)
    merged.update(confirmed)
    rows = sorted(merged.values(), key=lambda x: parse_date(x["start"]))
    now = datetime.now(timezone.utc)

    current = [x for x in rows if parse_date(x["start"]) <= now < parse_date(x["end"])]
    future = [x for x in rows if parse_date(x["start"]) > now]
    history = [x for x in rows if parse_date(x["end"]) <= now]
    return {
        "id": game_id, "name": game_name,
        "current": current, "next": future[:1], "upcoming": future[1:],
        "history": list(reversed(history)), "source": "G-Codus banner Excel tables",
    }


def main():
    BANNERS.mkdir(parents=True, exist_ok=True)
    games = [build_game(gid, name, str(i)) for i, (gid, (name, _)) in enumerate(GAMES.items(), 1)]
    payload = {
        "version": 6,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": "G-Codus banner Excel tables",
        "games": {g["name"]: g for g in games},
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Generated banner feed exclusively from banners/*.xlsx")


if __name__ == "__main__":
    main()
