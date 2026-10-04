"""Cross-check captured ACK-loss demos; this reads local evidence, not a live UI test."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parents[1] / "evidence" / "ack-loss"


def history(name):
    messages = json.loads((evidence / name).read_text())["messages"]
    assert len({m["id"] for m in messages}) == len(messages), "Duplicate server IDs"
    assert len({(m["roomId"], m["senderId"], m["clientMessageId"]) for m in messages}) == len(messages), "Duplicate logical messages"
    return messages


def texts(name):
    return [n.get("text") for n in ET.parse(evidence / name).iter("node")]


def added(before, after):
    assert len(after) == len(before) + 1
    assert after[:-1] == before
    return after[-1]


before = history("history-both-before.json")
unknown = history("history-both-unknown-final.json")
retried = history("history-both-retry-final.json")
message = added(before, unknown)
assert retried == unknown, "Same-ID retry changed server history"
client_id, server_id, sequence = message["clientMessageId"], message["id"], message["sequence"]
pending_ui = texts("ui-both-sending-final.xml")
unknown_ui = texts("ui-both-unknown-final.xml")
sent_ui = texts("ui-both-sent-final.xml")
assert "전송 중" in pending_ui
assert unknown_ui.count(message["text"]) == 1 and "결과 미확인" in unknown_ui
assert "같은 ID로 재시도" in unknown_ui and f"client ID {client_id[:8]}" in unknown_ui
assert sent_ui.count(message["text"]) == 1 and f"서버 수락 · #{sequence}" in sent_ui
assert "결과 미확인" not in sent_ui and "같은 ID로 재시도" not in sent_ui
android = (evidence / "android-both-final.log").read_text()
attempt = f"HTTP attempt sender=alice clientId={client_id} retry=false text={message['text']}"
timeout = f"HTTP timeout/error clientId={client_id} resultingStatus=UNKNOWN"
retry = f"HTTP attempt sender=alice clientId={client_id} retry=true text={message['text']}"
accepted = f"HTTP accepted sender=alice sequence={sequence} id={server_id} clientId={client_id} status=200"
assert android.index(attempt) < android.index(timeout) < android.index(retry) < android.index(accepted)
proxy = (evidence / "proxy-both-final.log").read_text()
for status in (201, 200):
    assert f"PROXY_UPSTREAM_ACCEPTED mode=BOTH status={status} clientId={client_id} id={server_id} sequence={sequence}" in proxy
assert f"PROXY_HIDE_WS clientId={client_id}" in proxy
assert f"PROXY_HOLD_HTTP_AFTER_ACCEPT clientId={client_id}" in proxy
assert not any("WS receive" in line and client_id in line for line in android.splitlines())
print(f"BOTH_LOST_PASS history {len(before)} → {len(unknown)} → {len(retried)}; same ID/sequence #{sequence}; one UI row")

before = history("history-http-before.json")
after = history("history-http-after.json")
message = added(before, after)
client_id, server_id, sequence = message["clientMessageId"], message["id"], message["sequence"]
ui = texts("ui-http-after-timeout.xml")
assert ui.count(message["text"]) == 1 and f"서버 수락 · #{sequence}" in ui
assert "결과 미확인" not in ui and "같은 ID로 재시도" not in ui
android = (evidence / "android-http-only.log").read_text()
ws = f"WS receive sender=alice sequence={sequence} id={server_id} clientId={client_id} text={message['text']}"
timeout = f"HTTP timeout/error clientId={client_id} resultingStatus=SENT"
assert android.index(ws) < android.index(timeout)
assert not any(client_id in line and ("resultingStatus=UNKNOWN" in line or "retry=true" in line) for line in android.splitlines())
proxy = (evidence / "proxy-http-only.log").read_text()
assert f"PROXY_UPSTREAM_ACCEPTED mode=HTTP_ONLY status=201 clientId={client_id} id={server_id} sequence={sequence}" in proxy
assert f"PROXY_HOLD_HTTP_AFTER_ACCEPT clientId={client_id}" in proxy
assert f"PROXY_HIDE_WS clientId={client_id}" not in proxy
print(f"HTTP_ONLY_PASS history {len(before)} → {len(after)}; WS acceptance survives HTTP timeout; one UI row #{sequence}")
