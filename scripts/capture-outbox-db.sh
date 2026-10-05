#!/usr/bin/env bash
set -euo pipefail
outbox_serial=${1:-}
outbox_label=${2:-}
outbox_adb=${CHAT_ADB:-adb}
outbox_group=${CHAT_EVIDENCE_GROUP:-outbox}
if [[ "$outbox_group" != outbox && "$outbox_group" != cache ]]; then
    echo 'CHAT_EVIDENCE_GROUP must be outbox or cache.' >&2
    exit 2
fi
if [ -z "$outbox_serial" ] || [[ ! "$outbox_label" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo 'Usage: bash scripts/capture-outbox-db.sh <observed-serial> <snapshot-label>' >&2
    exit 2
fi
if "$outbox_adb" -s "$outbox_serial" shell pidof dev.chatlab > /dev/null 2>&1; then
    echo 'Stop only dev.chatlab first. A stopped process gives a consistent DB + WAL capture.' >&2
    exit 2
fi
outbox_root=$(cd "$(dirname "$0")/.." && pwd)
outbox_destination="$outbox_root/evidence/$outbox_group/$outbox_label.db"
mkdir -p "$outbox_root/evidence/$outbox_group"
"$outbox_adb" -s "$outbox_serial" exec-out run-as dev.chatlab cat databases/chat-outbox.db > "$outbox_destination"
for outbox_suffix in -wal -shm; do
    if "$outbox_adb" -s "$outbox_serial" shell run-as dev.chatlab test -f "databases/chat-outbox.db$outbox_suffix"; then
        "$outbox_adb" -s "$outbox_serial" exec-out run-as dev.chatlab cat "databases/chat-outbox.db$outbox_suffix" > "$outbox_destination$outbox_suffix"
    else
        rm -f "$outbox_destination$outbox_suffix"
    fi
done
echo "Captured this debug app's stopped DB/WAL: $outbox_destination"
