from pathlib import Path
import re
from openpyxl import load_workbook

ROOT = Path(".")
SEED = ROOT / "library" / "seed"
BANNERS = ROOT / "banners"

CONFIG = [
    ("01_Wuthering_Waves", "3.7 Phase 2", "2026-10-22", "2026-11-11", ["Suoming", "Lynae", "Lucilla"], []),
    ("02_Genshin_Impact", "7.1 Phase 2", "2026-10-13", "2026-11-03", ["Skirk", "Escoffier"], []),
    ("03_Honkai_Star_Rail", "4.6 Phase 2", "2026-10-21", "2026-11-10", ["Pearl", "Mortenax Blade"], ["Misha", "Xueyi", "Qingque"]),
    ("04_Arknights_Endfield", "1.6 Phase 1", "2026-10-15", "2026-11-05", ["Si"], []),
    ("05_Zenless_Zone_Zero", "3.3 Phase 1", "2026-10-21", "2026-11-11", ["Phoenix"], []),
]
ID_RE = re.compile(r"^\d+\.\d+$")

def norm(v):
    return " ".join(str(v or "").strip().lower().split())

def seed_map(base, wanted):
    path = SEED / f"{base}.xlsx"
    wb = load_workbook(path, read_only=True, data_only=True)
    ws = wb.active
    headers = [norm(c.value) for c in ws[1]]
    name_headers = {"имя персонажа", "имя", "name"}
    id_headers = {"id"}
    name_col = next((i for i,h in enumerate(headers) if h in name_headers), None)
    id_col = next((i for i,h in enumerate(headers) if h in id_headers), None)
    if name_col is None or id_col is None:
        raise RuntimeError(f"{path}: cannot find name/id columns; headers={headers}")
    result = {}
    for wanted_name in wanted:
        matches = []
        for row in ws.iter_rows(min_row=2, values_only=True):
            name = str(row[name_col] or "").strip()
            cid = str(row[id_col] or "").strip()
            if name == wanted_name and ID_RE.fullmatch(cid):
                matches.append(cid)
        if len(matches) != 1:
            raise RuntimeError(f"Cannot uniquely map English name {wanted_name} in {base}: {matches}")
        result[wanted_name] = matches[0]
    wb.close()
    return result

def update_table(base, phase, start, end, five_names, four_names):
    ids = seed_map(base, five_names + four_names)
    path = BANNERS / f"{base}_confirmed.xlsx"
    wb = load_workbook(path)
    ws = wb.active
    headers = [norm(c.value) for c in ws[1]]
    required = ["версия и фаза", "дата начала", "дата окончания", "персонажи в составе баннера", "4* в баннере"]
    if any(x not in headers for x in required):
        raise RuntimeError(f"{path}: unexpected banner headers {headers}")
    idx = {h: headers.index(h) + 1 for h in required}
    phase_col, start_col, end_col, chars_col, four_col = [idx[x] for x in required]
    target = next((r for r in range(2, ws.max_row + 1) if norm(ws.cell(r, phase_col).value) == norm(phase)), None)
    if target is None:
        target = ws.max_row + 1
    ws.cell(target, phase_col).value = phase
    ws.cell(target, start_col).value = start
    ws.cell(target, end_col).value = end
    ws.cell(target, chars_col).value = ", ".join(ids[x] for x in five_names)
    ws.cell(target, four_col).value = ", ".join(ids[x] for x in four_names)
    wb.save(path)
    print(base, phase, ids)

for item in CONFIG:
    update_table(*item)
print("Confirmed banner update completed.")
