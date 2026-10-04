"""Read captured local DB/WAL, UI, logs and server history; this is not a live UI test."""
import json
from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
evidence = root / "evidence" / "outbox"


def database(name):
    path = evidence / name
    assert path.exists(), f"Missing capture: {path}"
    with sqlite3.connect(path) as db:
        db.row_factory = sqlite3.Row
        rows = [dict(row) for row in db.execute("SELECT * FROM outbox ORDER BY userId, roomId, clientMessageId")]
    assert len({(r["userId"], r["roomId"], r["clientMessageId"]) for r in rows}) == len(rows)
    return rows


def one(rows, text):
    matches = [row for row in rows if row["text"] == text]
    assert len(matches) == 1, (text, len(matches))
    return matches[0]


def history(name):
    rows = json.loads((evidence / name).read_text())["messages"]
    assert len({r["id"] for r in rows}) == len(rows)
    assert len({(r["senderId"], r["roomId"], r["clientMessageId"]) for r in rows}) == len(rows)
    return rows


def texts(name):
    return [node.get("text") for node in ET.parse(evidence / name).iter("node")]


def receipt_matches(row, message):
    assert (row["userId"], row["roomId"], row["clientMessageId"], row["text"], row["status"], row["serverId"], row["sequence"]) == (
        message["senderId"], message["roomId"], message["clientMessageId"], message["text"], "SENT", message["id"], message["sequence"])


before, unknown, retried = (history(name) for name in ("history-before.json", "history-both-unknown.json", "history-both-retry.json"))
assert len(unknown) == len(before) + 1 and unknown == retried
message = one(retried, "both3")
receipt_matches(one(database("both-sent.db"), "both3"), message)
assert "결과 미확인" in texts("ui-both3-after-timeout.xml")
assert texts("ui-both-sent.xml").count("both3") == 1 and "결과 미확인" not in texts("ui-both-sent.xml")
log = (evidence / "android-both-sent.log").read_text()
lines = [line for line in log.splitlines() if message["clientMessageId"] in line]
positions = [next(i for i, line in enumerate(lines) if marker in line) for marker in (
    "OUTBOX_COMMITTED", "retry=false", "resultingStatus=UNKNOWN", "retry=true", "status=200")]
assert positions == sorted(positions)
print("BOTH_OUTBOX_PASS: commit before POST; UNKNOWN -> same-ID retry; one server row and SENT receipt")

message = one(history("history-http.json"), "http3")
receipt_matches(one(database("http-sent.db"), "http3"), message)
ui = texts("ui-http3-after-timeout.xml")
assert ui.count("http3") == 1 and "결과 미확인" not in ui and "같은 ID로 재시도" not in ui
log = (evidence / "android-http3.log").read_text()
lines = [line for line in log.splitlines() if message["clientMessageId"] in line]
assert next(i for i, line in enumerate(lines) if "WS receive" in line) < next(i for i, line in enumerate(lines) if "resultingStatus=SENT" in line)
print("HTTP_ONLY_OUTBOX_PASS: WS acceptance survives late HTTP timeout in both UI and DB")

before_db, after_db = database("unknown-before-restart.db"), database("unknown-after-restart.db")
assert before_db == after_db
pending = one(before_db, "persist3")
assert pending["status"] == "UNKNOWN" and pending["serverId"] is None
old_pid, new_pid = ((evidence / name).read_text().strip() for name in ("pid-persist-before.txt", "pid-persist-after.txt"))
assert old_pid and new_pid and old_pid != new_pid
assert texts("ui-unknown-restored.xml").count("persist3") == 1 and "결과 미확인" in texts("ui-unknown-restored.xml")
assert "persist3" not in texts("ui-bob-offline.xml") and texts("ui-alice-offline.xml").count("persist3") == 1
unaccepted, reconnected = history("history-persist-unknown.json"), history("history-persist-before-retry.json")
assert unaccepted == reconnected and not any(m["text"] == "persist3" for m in reconnected)
for line in (evidence / "android-unknown-restored.log").read_text().splitlines():
    if "HTTP attempt" in line and pending["clientMessageId"] in line:
        assert line.split()[2] != new_pid, "Restart automatically posted an UNKNOWN row"
message = one(history("history-persist-after-retry.json"), "persist3")
sent = one(database("persist-sent.db"), "persist3")
for field in ("userId", "roomId", "clientMessageId", "text", "createdAtMillis"):
    assert sent[field] == pending[field]
receipt_matches(sent, message)
assert texts("ui-persist-sent.xml").count("persist3") == 1
print("UNKNOWN_RESTART_PASS: PID changed; all DB fields preserved; account isolated; no automatic POST; same-ID manual retry")

killed = one(database("sending-killed-final.db"), "kill3")
recovered = one(database("sending-recovered-final.db"), "kill3")
assert killed["status"] == "SENDING" and recovered == {**killed, "status": "UNKNOWN"}
assert texts("ui-sending-restored-final.xml").count("kill3") == 1 and "결과 미확인" in texts("ui-sending-restored-final.xml")
log = (evidence / "android-sending-restored-final.log").read_text()
restore = [line for line in log.splitlines() if "OUTBOX_RESTORED" in line][-1]
assert "recoveredSending=1" in restore
restarted_pid = restore.split()[2]
assert restarted_pid != (evidence / "pid-kill3-before.txt").read_text().strip()
assert not any("HTTP attempt" in line and killed["clientMessageId"] in line and line.split()[2] == restarted_pid for line in log.splitlines())
history_before = history("history-sending-killed-final.json")
final = history("history-final.json")
assert history_before == final, "Snapshot reconciliation unexpectedly posted a message"
receipt_matches(one(database("final-sent.db"), "kill3"), one(final, "kill3"))
print("SENDING_RESTART_PASS: accepted on server before kill; same persisted row -> UNKNOWN -> snapshot SENT; no duplicate POST")

ui = texts("ui-final-connected.xml")
assert "연결됨" in ui and "결과 미확인" not in ui and not any(text and "실험" in text for text in ui)
for message in final:
    assert ui.count(message["text"]) == 1
    receipt_matches(one(database("final-sent.db"), message["text"]), message)
reverse = (evidence / "reverse-final.txt").read_text()
assert "tcp:8080" in reverse and "tcp:8081" not in reverse and "tcp:8082" not in reverse
print(f"DEFAULT_RESTORED_PASS: {len(final)} accepted UI rows/receipts; only reverse 8080")
