#!/usr/bin/env python3
"""Compare ignored local FCM proof. Does not read any credential, FID, or configuration file."""
import json
from pathlib import Path
import sqlite3
ROOT=Path(__file__).resolve().parents[1]/'evidence'/'fcm'
def read(name):return json.loads((ROOT/name).read_text())
registration=read('registration-summary.json');send=read('send-summary.json');background=read('background-summary.json');audit=read('project-audit.json')
assert registration['sdkOnRegisteredConfirmed'] and registration['owner']=='alice' and registration['fidPresent']
assert not registration['fidPrintedOrStoredInEvidence']
assert send['state']=='accepted' and send['httpStatus']==200 and send['singleFidTarget'] and send['account']=='alice'
assert registration['projectId']==send['projectId']
assert audit['billing']['billingEnabled'] is False
assert audit['fcmService']['state']=='ENABLED' and audit['existingSendPermission']['permissions']==['cloudmessaging.messages.create']
print('PASS SDK onRegistered receipt and single Alice FID send accepted without billing or new permissions')
assert background['actualDataCallback'] and background['workerHttpSyncCompleted'] and background['newBackgroundProcess']
assert background['previousPid']!=background['backgroundPid'] and not background['foregroundOrWebSocketStarted']
log=(ROOT/'android-background.log').read_text();tail=log[log.rfind('PROCESS_BACKGROUND socketStopped'):]
assert 'FCM_HINT_RECORDED owner=alice through=25' in tail and 'PUSH_SYNC_COMPLETE owner=alice confirmed=25 target=25' in tail
assert 'WS_START' not in tail and 'PROCESS_FOREGROUND' not in tail
print('PASS stopped=false process kill followed by actual new background callback and HTTP-only worker')
with sqlite3.connect(ROOT/'fcm-before-send.db') as before,sqlite3.connect(ROOT/'fcm-background.db') as after:
 assert before.execute('SELECT baseSequence,contiguousThrough,requestedThrough FROM sync_cursors').fetchall()==[(0,0,0)]
 assert before.execute('SELECT count(*) FROM cached_messages').fetchone()[0]==0
 assert after.execute('SELECT baseSequence,contiguousThrough,requestedThrough FROM sync_cursors').fetchall()==[(0,25,25)]
 after.row_factory=sqlite3.Row
 rows=[dict(row) for row in after.execute('SELECT * FROM cached_messages ORDER BY sequence')]
 history=read('history25.json')['messages'];assert len(rows)==len(history)==25
 for row,message in zip(rows,history):
  assert row['ownerId']=='alice'
  for local,server in [('serverId','id'),('clientMessageId','clientMessageId'),('text','text'),('sequence','sequence'),('serverInstanceId','serverInstanceId'),('senderId','senderId')]:assert row[local]==message[server]
 assert after.execute('SELECT throughSequence FROM sync_hints').fetchall()[0][0]==25
 assert after.execute("SELECT count(*) FROM cached_messages WHERE ownerId!='alice'").fetchone()[0]==0
print('PASS Room 1..25 exactly matches server, baseline0 retained, durable hint25 and account scope preserved')
