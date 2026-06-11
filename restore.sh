#!/bin/bash
# ============================================================
# Teliki — Restore from Backup
# Restores Firestore data from a backup JSON file
# Run: bash restore.sh backups/teliki_backup_XXXXXXXX_XXXXXX.json
# ============================================================

if [ -z "$1" ]; then
    echo "Usage: bash restore.sh <backup_file.json>"
    echo ""
    echo "Available backups:"
    ls -la backups/teliki_backup_*.json 2>/dev/null || echo "  No backups found"
    exit 1
fi

BACKUP_FILE="$1"
if [ ! -f "$BACKUP_FILE" ]; then
    echo "❌ File not found: $BACKUP_FILE"
    exit 1
fi

echo "⚠️  This will OVERWRITE existing data in Firestore!"
echo "   Backup file: $BACKUP_FILE"
echo ""
read -p "Continue? (yes/no): " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
    echo "Cancelled."
    exit 0
fi

PROJECT="kava-signage-2026"
BASE_URL="https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"

python3 -c "
import json, urllib.request, sys

with open('$BACKUP_FILE', 'r', encoding='utf-8') as f:
    backup = json.load(f)

base = '$BASE_URL'

def to_firestore_value(val):
    if val is None:
        return {'nullValue': None}
    if isinstance(val, bool):
        return {'booleanValue': val}
    if isinstance(val, int):
        return {'integerValue': str(val)}
    if isinstance(val, float):
        return {'doubleValue': val}
    if isinstance(val, str):
        if 'T' in val and val.endswith('Z') and len(val) > 20:
            return {'timestampValue': val}
        return {'stringValue': val}
    if isinstance(val, list):
        return {'arrayValue': {'values': [to_firestore_value(v) for v in val]}}
    if isinstance(val, dict):
        return {'mapValue': {'fields': {k: to_firestore_value(v) for k, v in val.items()}}}
    return {'nullValue': None}

def patch_doc(path, data):
    url = f'{base}/{path}'
    fields = {k: to_firestore_value(v) for k, v in data.items()}
    body = json.dumps({'fields': fields}).encode('utf-8')
    req = urllib.request.Request(url, data=body, method='PATCH',
        headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status
    except Exception as e:
        print(f'  ⚠ Error: {e}', file=sys.stderr)
        return None

print(f'🔄 Restoring from: $BACKUP_FILE')
print(f'   Backup timestamp: {backup.get(\"timestamp\", \"?\")}')
print(f'   Locations: {len(backup.get(\"locations\", []))}')
print()

for loc in backup.get('locations', []):
    loc_id = loc['id']
    loc_data = loc['data']
    name = loc_data.get('name', '?')
    print(f'📍 Restoring: {name} ({loc_id})')
    
    status = patch_doc(f'locations/{loc_id}', loc_data)
    print(f'   Location: {\"✅\" if status else \"❌\"}')
    
    for screen in loc.get('screens', []):
        screen_id = screen['id']
        screen_data = screen['data']
        status = patch_doc(f'locations/{loc_id}/screens/{screen_id}', screen_data)
        sched = screen_data.get('schedule', [])
        count = len(sched) if isinstance(sched, list) else 0
        print(f'   {screen_id}: {\"✅\" if status else \"❌\"} ({count} media)')

print()
print('✅ Restore complete!')
"
