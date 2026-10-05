#!/usr/bin/env python3
"""Read captured local evidence; does not start a server or modify an Android device."""
import json
from pathlib import Path
import re
import sqlite3
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'evidence' / 'paging'


def text(name):
    return (ROOT / name).read_text()


def database(name):
    return sqlite3.connect('file:' + str(ROOT / (name + '.db')) + '?mode=ro', uri=True)


def contents(name, table):
    with database(name) as db:
        return sorted(db.execute('SELECT * FROM ' + table).fetchall())


def ui(name):
    return [n.get('text') for n in ET.parse(ROOT / ('ui-' + name + '.xml')).iter('node')]


def anchor(name):
    lines = [line for line in text('android-' + name + '.log').splitlines() if 'SCROLL_ANCHOR user=alice' in line]
    match = re.search(r'key=(\S+) sequence=(\d+) index=(\d+) offset=(\d+)', lines[-1])
    assert match
    return match.groups()


def verify():
    initial = contents('initial20', 'cached_messages')
    assert len(initial) == 20 and sorted(row[7] for row in initial) == list(range(66, 86))
    key = contents('initial20', 'history_keys')[0]
    failed = contents('after-failed', 'history_keys')[0]
    assert key[3] == failed[3] and key[4] == failed[4] == 66 and not failed[6]
    assert len(contents('after-failed', 'cached_messages')) == 21
    assert '과거 조회 실패' in ui('older-failed') and '과거 조회 재시도' in ui('older-failed')
    requests = re.findall(r'PROXY_PAGE_REQUEST before=(\S+)', text('proxy-demo.log'))
    assert len(requests) == 5 and requests[0] == requests[1] == key[3]
    assert len(re.findall(r'PROXY_PAGE_FAILED before=', text('proxy-demo.log'))) == 1
    print('PASS bounded bootstrap, failed cursor retained across process restart, same-cursor manual query')

    for before, after in (('anchor-before', 'anchor-after'), ('anchor-older-before', 'older-retry')):
        first, second = anchor(before), anchor(after)
        assert first[0:2] == second[0:2] and first[3] == second[3], (first, second)
        assert int(second[2]) == int(first[2]) + 1
    during = text('android-older-retry.log')
    start = during.rfind('OLDER_REQUEST user=alice oldest=66')
    assert start >= 0
    segment = during[start:]
    assert segment.index('WS receive sender=bob sequence=87') < segment.index('OLDER_CACHED user=alice oldest=46')
    print('PASS same message key/offset while live arrives, including older HTTP in flight')

    history = json.loads(text('history-final.json'))
    with database('online-final') as db:
        cached = db.execute('SELECT serverId,sequence,text,serverInstanceId FROM cached_messages WHERE ownerId="alice" ORDER BY sequence').fetchall()
        assert cached == [(m['id'], m['sequence'], m['text'], m['serverInstanceId']) for m in history['messages']]
        assert [row[1] for row in cached] == list(range(1, 92))
        assert len({row[0] for row in cached}) == 91
        assert db.execute('SELECT COUNT(*) FROM cached_messages WHERE ownerId!="alice"').fetchone()[0] == 0
        assert db.execute('SELECT oldestSequence,highWatermark,endReached,nextBefore FROM history_keys').fetchone() == (1, 91, 1, None)
        assert db.execute('SELECT userId,roomId,text,status,sequence FROM outbox').fetchall() == [('alice', 'demo', 'own-paging', 'SENT', 88)]
    assert '현재 서버의 처음까지 확인' in ui('history-end-final')
    assert 'ROUND_TRIP_PASS' in text('peer-roundtrip.log')
    assert 'own-paging' in ui('roundtrip-final') and 'Bob reply: own-paging' in ui('roundtrip-final')
    print('PASS full #1..#91 history/Room identity, no duplicate, accepted outbox, Android/Bob round trip')

    background = text('android-background-after.log').rsplit('PROCESS_BACKGROUND socketStopped', 1)[1]
    assert 'WS receive' not in background and 'PROCESS_FOREGROUND' not in background
    returned = text('android-foreground-return-final.log')
    marker = returned.rfind('PROCESS_FOREGROUND')
    assert marker >= 0 and 'highWatermark=91' in returned[marker:]
    assert all(label in ui('foreground-return-final') for label in ('연결됨', 'background-090', 'background-091'))
    print('PASS observed background socket stop and bounded foreground bootstrap (no FCM claim)')

    assert not text('ports-offline.txt').strip()
    assert text('pid-online.txt').strip() != text('pid-offline.txt').strip()
    for table in ('cached_messages', 'cache_sessions', 'history_keys', 'outbox'):
        assert contents('online-final', table) == contents('offline-final', table), table
    assert '저장된 메시지가 없습니다.' in ui('offline-bob-final')
    assert not any('fixture-' in (label or '') for label in ui('offline-bob-final'))
    assert 'background-091' in ui('offline-alice-final')
    offline = ui('offline-older-final')
    assert '연결 끊김' in offline and any(re.fullmatch(r'fixture-0[0-6]\d', label or '') for label in offline)
    print('PASS new offline process reads downloaded older Room pages; all DB contents preserved')

    for group, expected in (('server-junit', 13), ('jvm-junit', 6), ('final-room-results', 32)):
        reports = [ET.parse(p).getroot() for p in (ROOT / group).rglob('TEST-*.xml')]
        assert sum(int(r.get('tests', 0)) for r in reports) == expected
        assert all(int(r.get(name, 0)) == 0 for r in reports for name in ('failures', 'errors', 'skipped'))
    print('PASS preserved JUnit results: server 13, JVM 6, Android 32')


if __name__ == '__main__':
    verify()
