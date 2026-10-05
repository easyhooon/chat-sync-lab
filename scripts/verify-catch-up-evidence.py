#!/usr/bin/env python3
"""Verify this Mac's ignored unit-six snapshots; never contact a remote service."""
import json
from pathlib import Path
import re
import sqlite3
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'evidence' / 'catch-up'


def database(label):
    connection = sqlite3.connect(ROOT / f'{label}.db')
    connection.row_factory = sqlite3.Row
    return connection


def cursor(db):
    rows = db.execute('SELECT * FROM sync_cursors').fetchall()
    assert len(rows) == 1
    row = dict(rows[0])
    assert (row['ownerId'], row['roomId']) == ('alice', 'demo')
    return row


def rows(db):
    return [dict(row) for row in db.execute('SELECT * FROM cached_messages ORDER BY sequence')]


with database('initial20') as initial, database('partial') as partial, database('recovered') as recovered:
    first, interrupted, complete = map(cursor, (initial, partial, recovered))
    assert [(c['baseSequence'], c['contiguousThrough'], c['requestedThrough']) for c in (first, interrupted, complete)] == [(65, 85, 85), (65, 105, 205), (65, 206, 206)]
    assert len({c['serverInstanceId'] for c in (first, interrupted, complete)}) == 1
    assert [row['sequence'] for row in rows(initial)] == list(range(66, 86))
    assert [row['sequence'] for row in rows(partial)] == list(range(66, 106)) + list(range(186, 206))
    print('PASS bootstrap baseline and partial cursor remain below highest received message')

    history = json.loads((ROOT / 'history206.json').read_text())
    expected = [message for message in history['messages'] if message['sequence'] > complete['baseSequence']]
    cached = rows(recovered)
    assert len(cached) == len(expected) == 141
    for row, message in zip(cached, expected):
        for local, server in [('serverId', 'id'), ('clientMessageId', 'clientMessageId'), ('roomId', 'roomId'), ('senderId', 'senderId'), ('text', 'text'), ('sequence', 'sequence'), ('createdAt', 'createdAt'), ('serverInstanceId', 'serverInstanceId')]:
            assert row[local] == message[server], (local, row['sequence'])
    assert len({row['stableKey'] for row in cached}) == 141
    assert recovered.execute('SELECT count(*) FROM outbox').fetchone()[0] == 0
    print('PASS recovered 66..206 exactly match server IDs, bodies, order and unique UI keys')

    old_key = partial.execute('SELECT nextBefore,oldestSequence,endReached FROM history_keys').fetchone()
    new_key = recovered.execute('SELECT nextBefore,oldestSequence,endReached FROM history_keys').fetchone()
    assert tuple(old_key) == tuple(new_key)
    assert new_key['oldestSequence'] == 186
    print('PASS after recovery does not consume the before cursor')

proxy = (ROOT / 'proxy.log').read_text()
requests = [(int(a), int(t)) for a, t in re.findall(r'PROXY_AFTER_REQUEST number=\d+ after=(\d+) through=(\d+)', proxy)]
assert [a for a, _ in requests] == [85, 105, 105, 125, 145, 165], requests
assert 'PROXY_AFTER_FAILED after=105' in proxy
assert requests[:3] == [(85, 205), (105, 205), (105, 205)]
assert all(target == 206 for _, target in requests[3:])
assert (ROOT / 'pid-partial.txt').read_text().strip() != (ROOT / 'pid-resumed.txt').read_text().strip()
log = (ROOT / 'android-final.log').read_text()
assert 'confirmed=125 target=206' in log
assert log.index('sequence=206') < log.index('confirmed=125 target=206')
assert 'confirmed=206 target=206' in log
assert log.index('PROCESS_BACKGROUND socketStopped') < log.index('highWatermark=205')
assert '누락 복구 재시도' in (ROOT / 'ui-partial-failed.xml').read_text()
assert 'live-during-after-206' in (ROOT / 'ui-recovered.xml').read_text()
print('PASS new process resumes after105; live206 raises target while gap remains; UI shows failure/completion')

with database('offline') as offline, database('recovered') as recovered:
    for table in ('outbox', 'cache_sessions', 'cached_messages', 'history_keys', 'sync_cursors', 'sync_hints'):
        assert sorted(map(tuple, offline.execute(f'SELECT * FROM {table}'))) == sorted(map(tuple, recovered.execute(f'SELECT * FROM {table}'))), table
    nodes = list(ET.parse(ROOT / 'ui-offline-older.xml').iter('node'))
    labels = [node.get('text', '') for node in nodes]
    # latest bootstrap covered only 186..205; this page must come from recovered Room rows.
    assert any(label.startswith('missed-') and 1 <= int(label.split('-')[-1]) <= 100 for label in labels), labels
print('PASS offline new process reads recovered older rows; all six DB tables preserved')
