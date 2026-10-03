from pathlib import Path
import json

p = Path('app/src/main/assets/codes_feed.json')
if not p.exists():
    raise SystemExit('codes_feed.json not found')
data = json.loads(p.read_text())
data.setdefault('active', [])
data.setdefault('expired', [])
# Preserve any already-known expired entries and never duplicate codes.
expired = {str(x.get('code','')).strip().upper(): x for x in data['expired'] if x.get('code')}
active = []
for item in data['active']:
    code = str(item.get('code','')).strip().upper()
    if not code:
        continue
    item['code'] = code
    # Entries explicitly marked expired/inactive belong in history.
    status = str(item.get('status', item.get('source_status',''))).lower()
    if status in {'expired','inactive','invalid'}:
        expired[code] = item
    else:
        active.append(item)
data['active'] = [x for x in active if str(x.get('code','')).upper() not in expired]
data['expired'] = list(expired.values())
p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
