#!/bin/bash
# ============================================================
# Teliki — Full Backup Script
# Exports all Firestore data + Storage file URLs to JSON
# Run: bash backup.sh
# ============================================================

BACKUP_DIR="backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/teliki_backup_$TIMESTAMP.json"
PROJECT="kava-signage-2026"
BASE_URL="https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"

mkdir -p "$BACKUP_DIR"

echo "🔄 Teliki Backup — $TIMESTAMP"
echo "================================"

python3 -c "
import json, urllib.request, sys

base = '$BASE_URL'

def fetch(url):
    try:
        req = urllib.request.Request(url, headers={'Accept': 'application/json'})
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f'  ⚠ Error fetching {url}: {e}', file=sys.stderr)
        return {}

def parse_value(v):
    if 'stringValue' in v: return v['stringValue']
    if 'integerValue' in v: return int(v['integerValue'])
    if 'doubleValue' in v: return v['doubleValue']
    if 'booleanValue' in v: return v['booleanValue']
    if 'timestampValue' in v: return v['timestampValue']
    if 'nullValue' in v: return None
    if 'arrayValue' in v:
        vals = v['arrayValue'].get('values', [])
        return [parse_value(x) for x in vals]
    if 'mapValue' in v:
        fields = v['mapValue'].get('fields', {})
        return {k: parse_value(fv) for k, fv in fields.items()}
    return None

def parse_doc(doc):
    data = {}
    for k, v in doc.get('fields', {}).items():
        data[k] = parse_value(v)
    return data

# Fetch all locations
print('📍 Fetching locations...')
loc_data = fetch(f'{base}/locations')
locations = loc_data.get('documents', [])
print(f'   Found {len(locations)} locations')

backup = {
    'version': 1,
    'timestamp': '$TIMESTAMP',
    'project': '$PROJECT',
    'locations': []
}

for loc_doc in locations:
    loc_id = loc_doc['name'].split('/')[-1]
    loc_fields = parse_doc(loc_doc)
    print(f'   📦 {loc_fields.get(\"name\", \"?\")} ({loc_id})')

    # Fetch screens
    screens_data = fetch(f'{base}/locations/{loc_id}/screens')
    screens = []
    for screen_doc in screens_data.get('documents', []):
        screen_id = screen_doc['name'].split('/')[-1]
        screen_fields = parse_doc(screen_doc)
        screens.append({
            'id': screen_id,
            'data': screen_fields
        })
        sched = screen_fields.get('schedule', [])
        if sched:
            for item in sched:
                if isinstance(item, dict):
                    print(f'      🖥 {screen_id}: {item.get(\"file_name\", \"?\")}')

    backup['locations'].append({
        'id': loc_id,
        'data': loc_fields,
        'screens': screens
    })

# Save
with open('$BACKUP_FILE', 'w', encoding='utf-8') as f:
    json.dump(backup, f, ensure_ascii=False, indent=2)

print()
print(f'✅ Backup saved: $BACKUP_FILE')
print(f'   {len(backup[\"locations\"])} locations backed up')
"

echo ""
echo "Done! Backup: $BACKUP_FILE"
