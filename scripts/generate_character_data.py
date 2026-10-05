from pathlib import Path
import json
import re
from datetime import datetime, timezone
from openpyxl import load_workbook

ROOT = Path(__file__).resolve().parents[1]
TABLES = {
    "1_wuthering_waves.xlsx": ("1", "wuwa", "Wuthering Waves"),
    "2_genshin_impact.xlsx": ("2", "genshin", "Genshin Impact"),
    "3_honkai_star_rail.xlsx": ("3", "starrail", "Honkai: Star Rail"),
    "4_arknights_endfield.xlsx": ("4", "endfield", "Arknights: Endfield"),
    "5_zenless_zone_zero.xlsx": ("5", "zzz", "Zenless Zone Zero"),
}
OUT = ROOT / "data" / "characters.json"
IMAGE_BASE = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/images"
ALIASES = {"Имя персонажа":"name", "Имя":"name", "Стихия":"element", "Элемент":"element", "Редкость":"rarity", "Rarity":"rarity", "ID":"id"}

def parse_rarity(v):
    m = re.search(r"\d+", str(v or ""))
    return int(m.group()) if m else 0

def process(path, prefix, game_id, game_name):
    wb = load_workbook(path)
    ws = wb.active
    headers = [str(c.value or "").strip() for c in ws[1]]
    mapped = [ALIASES.get(h, h) for h in headers]
    if "Портрет" in headers or "Portrait" in headers:
        raise SystemExit(f"{path}: remove the Portrait column; portraits are images/<ID>.webp")
    required = {"name", "element", "rarity"}
    if not required.issubset(set(mapped)):
        raise SystemExit(f"{path}: required columns are Имя персонажа, Стихия, Редкость")
    if "id" not in mapped:
        ws.insert_cols(1)
        ws.cell(1, 1).value = "ID"
        mapped.insert(0, "id")
    id_col = mapped.index("id") + 1
    name_col = mapped.index("name") + 1
    element_col = mapped.index("element") + 1
    rarity_col = mapped.index("rarity") + 1
    rows = []
    for r in range(2, ws.max_row + 1):
        name = str(ws.cell(r, name_col).value or "").strip()
        if not name:
            continue
        rows.append({"row": r, "id": str(ws.cell(r, id_col).value or "").strip(), "name": name, "element": str(ws.cell(r, element_col).value or "").strip(), "rarity": parse_rarity(ws.cell(r, rarity_col).value)})
    used = {int(x["id"].split(".")[1]) for x in rows if re.fullmatch(rf"{prefix}\.\d+", x["id"])}
    counter = 1
    for x in rows:
        if not x["id"]:
            while counter in used:
                counter += 1
            x["id"] = f"{prefix}.{counter}"
            used.add(counter)
            ws.cell(x["row"], id_col).value = x["id"]
            counter += 1
        elif not re.fullmatch(rf"{prefix}\.\d+", x["id"]):
            raise SystemExit(f"{path}: invalid ID {x['id']} at row {x['row']}; expected {prefix}.N")
    result = [{"id":x["id"], "gameId":game_id, "name":x["name"], "element":x["element"], "rarity":x["rarity"], "announced":False, "portraitUrl":f"{IMAGE_BASE}/{x['id']}.webp"} for x in rows]
    wb.save(path)
    return {"id":prefix, "gameId":game_id, "name":game_name, "characters":result}

def main():
    base = ROOT / "data" / "characters"
    games = []
    for filename, meta in TABLES.items():
        path = base / filename
        if not path.exists():
            raise SystemExit(f"Missing source: {path}")
        games.append(process(path, *meta))
    payload = {"version":1, "generatedAt":datetime.now(timezone.utc).isoformat(), "games":games}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

if __name__ == "__main__":
    main()
