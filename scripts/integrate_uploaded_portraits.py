#!/usr/bin/env python3
import json
import re
import subprocess
from pathlib import Path

ROOT = Path('.')
ASSET_ROOT = ROOT / 'app' / 'src' / 'main' / 'assets'
MANIFEST = ROOT / 'data' / 'gacha_character_manifest.json'

# Exact Endfield roster currently represented by the uploaded portrait set.
ENDFIELD = {
    'akekuri','alesh','antal','arcane','arclight','ardelia','avywenna','camille','catcher',
    'chen-qianyu','da-pan','ember','endministrator','estella','fluorite','gilberta',
    'laevatain','last-rite','lifeng','liino','mi-fu','pogranichnik','purrchena','rossi',
    'si','snowshine','tangtang','typhoeus','wulfgard','xaihi','yvonne','zhuang-fangyi'
}

def stem_name(path: Path) -> str:
    return re.sub(r'_card$', '', path.stem).lower()

def slug(s: str) -> str:
    s = s.lower().replace('’', '').replace("'", '').replace('&', 'and')
    return re.sub(r'[^a-z0-9]+', '-', s).strip('-')

# Read old manifest so we never reclassify the existing three games.
old = json.loads(MANIFEST.read_text())
old_files = {x['file'] for x in old.get('images', [])}

files = sorted(ROOT.glob('*_card.webp'))
if not files:
    print('No root *_card.webp files found.')
    raise SystemExit(0)

end_dir = ASSET_ROOT / 'arknights_endfield'
hsr_dir = ASSET_ROOT / 'honkai_star_rail'
end_dir.mkdir(parents=True, exist_ok=True)
hsr_dir.mkdir(parents=True, exist_ok=True)

moved = []
for src in files:
    if src.name in old_files:
        continue
    s = stem_name(src)
    game = 'Arknights: Endfield' if s in ENDFIELD else 'Honkai: Star Rail'
    dst_dir = end_dir if game == 'Arknights: Endfield' else hsr_dir
    dst = dst_dir / src.name
    if dst.exists():
        if src.read_bytes() == dst.read_bytes():
            src.unlink()
            continue
        raise SystemExit(f'Collision with different content: {dst}')
    subprocess.run(['git', 'mv', str(src), str(dst)], check=True)
    moved.append((game, src.name))

# Rebuild/extend the manifest using the actual files that now live in the app assets.
images = [x for x in old.get('images', [])]
seen = {(x['game'], x['file']) for x in images}
for game, filename in moved:
    key = (game, filename)
    if key in seen:
        continue
    name = filename[:-10].replace('-', ' ').strip().title()
    # Preserve known official spellings where the filename is clear.
    aliases = {
        'blade-mortenax': 'Mortenax Blade',
        'dan-heng-permansor-terrae': 'Dan Heng • Permansor Terrae',
        'march-7th-evernight': 'March 7th • Evernight',
        'march-7th-swordmaster': 'March 7th • The Hunt',
        'silver-wolf-lv-999': 'Silver Wolf • Lv. 999',
        'topaz': 'Topaz & Numby',
        'himeko-nova': 'Himeko Nova',
        'rin-tohsaka': 'Rin Tohsaka',
        'robin-summeretto': 'Robin Summeretto',
        'tingyun-fugue': 'Tingyun • Fugue',
        'the-dahlia': 'The Dahlia',
        'the-herta': 'The Herta',
        'zhuang-fangyi': 'Zhuang Fangyi',
        'chen-qianyu': 'Chen Qianyu',
        'da-pan': 'Da Pan',
        'last-rite': 'Last Rite',
        'mi-fu': 'Mi Fu',
        'pogranichnik': 'Pogranichnik',
    }
    name = aliases.get(filename[:-10], name)
    images.append({'game': game, 'character': name, 'file': filename})
    seen.add(key)

counts = {}
for x in images:
    counts[x['game']] = counts.get(x['game'], 0) + 1
old['total_images'] = len(images)
old['games'] = counts
old['images'] = images
MANIFEST.write_text(json.dumps(old, ensure_ascii=False, indent=2) + '\n')

print(f'Integrated {len(moved)} uploaded portraits.')
print('Counts:', counts)
