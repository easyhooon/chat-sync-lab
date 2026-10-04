#!/usr/bin/env bash
set -euo pipefail
chat_mode=${1:-}
chat_serial=${2:-}
chat_adb=${CHAT_ADB:-adb}
case "$chat_mode" in
    both) chat_port=8081 ;;
    http-only) chat_port=8082 ;;
    *) echo 'Usage: bash scripts/ack-loss-lab.sh both|http-only <observed-device-serial>' >&2; exit 2 ;;
esac
if [ -z "$chat_serial" ]; then echo 'Select a device serial from adb devices -l first.' >&2; exit 2; fi
curl --fail --silent -H 'X-Test-User: alice' "http://127.0.0.1:$chat_port/rooms/demo/messages" > /dev/null
"$chat_adb" -s "$chat_serial" reverse "tcp:$chat_port" "tcp:$chat_port"
"$chat_adb" -s "$chat_serial" shell am force-stop dev.chatlab
"$chat_adb" -s "$chat_serial" shell am start -n dev.chatlab/.MainActivity --es ack_loss_lab "$chat_mode"
echo "Local debug lab: $chat_mode. Wait for connected, send one new Alice message, then keep the app in foreground for 8 seconds."
echo 'Do not reconnect during UNKNOWN inspection: a fresh snapshot would already confirm acceptance.'
