from pathlib import Path
from openpyxl import load_workbook

ROOT=Path(".")
SEED=ROOT/"library"/"seed"
BANNERS=ROOT/"banners"

CONFIG=[
 ("01_Wuthering_Waves","3.7 Phase 2","2026-10-22","2026-11-11",["Suoming","Lynae","Lucilla"],[]),
 ("02_Genshin_Impact","7.1 Phase 2","2026-10-13","2026-11-03",["Skirk","Escoffier"],[]),
 ("03_Honkai_Star_Rail","4.6 Phase 2","2026-10-21","2026-11-10",["Pearl","Mortenax Blade"],["Misha","Xueyi","Qingque"]),
 ("04_Arknights_Endfield","1.6 Phase 1","2026-10-15","2026-11-05",["Si"],[]),
 ("05_Zenless_Zone_Zero","3.3 Phase 1","2026-10-21","2026-11-11",["Phoenix"],[]),
]

def n(v):
    return " ".join(str(v or "").strip().lower().replace("ё","е").split())

ALIASES={
 "Suoming":["Suoming","Суомин","Суоминь"],
 "Lynae":["Lynae","Линнея","Линаэ"],
 "Lucilla":["Lucilla","Люцилла"],
 "Skirk":["Skirk","Скирк"],
 "Escoffier":["Escoffier","Эскофье"],
 "Pearl":["Pearl","Перл"],
 "Mortenax Blade":["Mortenax Blade","Мортенакс Блейд"],
 "Misha":["Misha","Миша"],
 "Xueyi":["Xueyi","Сюэйи","Сюэи"],
 "Qingque":["Qingque","Цинцюэ","Цинцю"],
 "Si":["Si","Си"],
 "Phoenix":["Phoenix","Феникс"],
}

def seed_map(base, wanted):
    wb=load_workbook(SEED/(base+".xlsx"),read_only=True,data_only=True)
    ws=wb.active
    rows=list(ws.iter_rows(values_only=True))
    wb.close()
    result={}
    for wanted_name in wanted:
        candidates=[n(x) for x in ALIASES.get(wanted_name,[wanted_name])]
        matches=[]
        for row in rows[1:]:
            cells=[str(v or "").strip() for v in row]
            if not any(n(v) in candidates for v in cells):
                continue
            ids=[v for v in cells if __import__("re").match(r"^\\d+\\.\\d+$",v)]
            if len(ids)==1:
                matches.append(ids[0])
        if len(set(matches))!=1:
            raise RuntimeError(f"Cannot uniquely map {wanted_name} in {base}: {matches[:10]}")
        result[wanted_name]=matches[0]
    return result

