#!/usr/bin/env bash
set -euo pipefail
chat_tmp=$(mktemp -d)
trap 'rm -rf "$chat_tmp"' EXIT
chat_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
chat_url=http://127.0.0.1:8080/rooms/demo/messages
curl --fail --silent -H 'X-Test-User: alice' "$chat_url" > "$chat_tmp/before.json"
for chat_case in first replay conflict; do
    chat_text=idempotency-probe
    if [ "$chat_case" = conflict ]; then chat_text=changed-text; fi
    chat_status=$(curl --silent --show-error -o "$chat_tmp/$chat_case.json" -w '%{http_code}' \
        -H 'X-Test-User: alice' -H 'Content-Type: application/json' \
        -d "{\"clientMessageId\":\"$chat_id\",\"text\":\"$chat_text\"}" "$chat_url")
    echo "$chat_case HTTP $chat_status"
    echo "$chat_status" > "$chat_tmp/$chat_case.status"
done
curl --fail --silent -H 'X-Test-User: alice' "$chat_url" > "$chat_tmp/after.json"
python3 - "$chat_tmp" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
load = lambda name: json.loads((p / (name + '.json')).read_text())
assert [(p / (n + '.status')).read_text().strip() for n in ['first', 'replay', 'conflict']] == ['201', '200', '409']
assert load('first') == load('replay')
assert len(load('after')['messages']) == len(load('before')['messages']) + 1
print('IDEMPOTENCY_PASS same server id / sequence; exactly one new history row')
PY
