"""Read-only cross-check of the local cache demo captures; requires evidence/cache/."""
import json
from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parents[1] / "evidence" / "cache"


def load_json(name):
    return json.loads((evidence / name).read_text())


def texts(label):
    return [node.get("text") for node in ET.parse(evidence / f"ui-{label}.xml").iter("node") if node.get("text")]


def database(label):
    connection = sqlite3.connect(f"file:{evidence / (label + '.db')}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    assert connection.execute("PRAGMA user_version").fetchone()[0] == 2
    return connection


def cache_rows(connection):
    return [dict(row) for row in connection.execute(
        "SELECT * FROM cached_messages ORDER BY ownerId, roomId, serverInstanceId, sequence")]


old_history = load_json("live-cache4-history.json")
assert len(old_history["messages"]) == 2
assert (evidence / "live-cache4-http.txt").read_text().splitlines() == ["201", "200"]
with database("online") as online, database("offline") as offline:
    before, after = cache_rows(online), cache_rows(offline)
    assert before == after and len(before) == 2
    for message in old_history["messages"]:
        row = next(row for row in before if row["serverId"] == message["id"])
        assert row["ownerId"] == "alice" and row["senderId"] == "bob" and row["roomId"] == "demo"
        assert all(row[key] == message[key] for key in ("clientMessageId", "text", "sequence", "createdAt", "serverInstanceId"))
assert (evidence / "pid-before.txt").read_text().strip() != (evidence / "pid-offline.txt").read_text().strip()
for label in ("offline-alice", "offline-alice-return"):
    view = texts(label)
    assert "history-cache4" in view and "live-cache4" in view and "연결 끊김" in view
assert "history-cache4" not in texts("offline-bob") and "live-cache4" not in texts("offline-bob")
assert "CACHE_RESTORED user=bob room=demo rows=0" in (evidence / "android-offline-bob.log").read_text()
print("PASS: same received rows survive a new offline process; account isolation; idempotent replay")

empty = load_json("restarted-empty-history.json")
assert empty["messages"] == [] and empty["serverInstanceId"] != old_history["serverInstanceId"]
assert all(text in texts("restarted-empty") for text in ("history-cache4", "live-cache4", "연결됨"))
restarted = load_json("restart-cache4-response.json")
assert restarted["sequence"] == 1 and restarted["serverInstanceId"] == empty["serverInstanceId"]
assert all(text in texts("restarted-new") for text in ("history-cache4", "live-cache4", "restart-cache4"))
print("PASS: empty restarted snapshot preserves cache; new #1 has a separate server namespace")

current = load_json("final-history.json")
assert len(current["messages"]) == 3
assert current["serverInstanceId"] == empty["serverInstanceId"]
assert "ROUND_TRIP_PASS historyCount=3" in (evidence / "bob-peer.log").read_text()
with database("final") as final:
    rows = cache_rows(final)
    assert len(rows) == 5 and all(row["ownerId"] == "alice" and row["roomId"] == "demo" for row in rows)
    assert len({row["stableKey"] for row in rows}) == 5
    assert all(row in rows for row in before)
    sessions = list(final.execute("SELECT serverInstanceId, ordinal FROM cache_sessions WHERE ownerId='alice' AND roomId='demo' ORDER BY ordinal"))
    assert [(row[0], row[1]) for row in sessions] == [(old_history["serverInstanceId"], 1), (current["serverInstanceId"], 2)]
    for message in current["messages"]:
        cached = next(row for row in rows if row["serverInstanceId"] == current["serverInstanceId"] and row["serverId"] == message["id"])
        assert all(cached[key] == message[key] for key in ("clientMessageId", "senderId", "text", "sequence", "createdAt"))
    own = next(message for message in current["messages"] if message["senderId"] == "alice")
    receipts = list(final.execute("SELECT * FROM outbox"))
    assert len(receipts) == 1
    receipt = dict(receipts[0])
    assert receipt["status"] == "SENT" and receipt["clientMessageId"] == own["clientMessageId"]
    assert receipt["serverId"] == own["id"] and receipt["sequence"] == own["sequence"]
    assert receipt["serverInstanceId"] == own["serverInstanceId"]
log = (evidence / "android-final-online.log").read_text()
assert log.index(f"OUTBOX_COMMITTED user=alice room=demo clientId={own['clientMessageId']}") < log.index(f"HTTP attempt sender=alice room=demo clientId={own['clientMessageId']}")
assert f"HTTP accepted sender=alice sequence=2 id={own['id']}" in log
assert f"BOB_WS_RECEIVED sender=alice sequence=2 id={own['id']}" in (evidence / "bob-peer.log").read_text()
assert "own-cache4" in texts("final-online") and "Bob reply: own-cache4" in texts("final-online")
print("PASS: Android -> Bob WS peer -> Android; five cache rows / three server rows; one SENT outbox receipt")
