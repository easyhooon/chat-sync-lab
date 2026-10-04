"""Cross-check captured local demo evidence; not a network client or a UI test."""
import json
import pathlib
import re
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
evidence = root / "evidence"
bob = (evidence / "bob-client.log").read_text()
android = (evidence / "android-round-trip.log").read_text()
messages = json.loads((evidence / "history.json").read_text())["messages"]
assert "ROUND_TRIP_PASS" in bob
alice_id = re.search(r"BOB_WS_RECEIVED sender=alice sequence=\d+ id=(\S+)", bob).group(1)
bob_id = re.search(r"BOB_HTTP_ACCEPTED sequence=\d+ id=(\S+)", bob).group(1)
by_id = {message["id"]: message for message in messages}
assert len(by_id) == len(messages), "Duplicate server IDs in history"
assert len({(m["senderId"], m["clientMessageId"]) for m in messages}) == len(messages)
assert f"id={alice_id}" in android and f"id={bob_id}" in android
assert re.search(rf"HTTP accepted sender=alice sequence=\d+ id={re.escape(alice_id)}", android)
assert re.search(rf"WS receive sender=bob sequence=\d+ id={re.escape(bob_id)}", android)
texts = [node.attrib.get("text") for node in ET.parse(evidence / "ui-round-trip.xml").iter("node")]
assert texts.count(by_id[alice_id]["text"]) == 1
assert texts.count(by_id[bob_id]["text"]) == 1
assert "연결됨" in texts
print(f"DEMO_EVIDENCE_PASS Alice #{by_id[alice_id]['sequence']} → Bob #{by_id[bob_id]['sequence']}; one UI row each")
