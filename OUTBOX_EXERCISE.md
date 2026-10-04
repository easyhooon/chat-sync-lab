# 15–20분 실습: 죽은 SENDING을 내가 설명할 수 있을까

완성 코드를 먼저 바꾸지 않는다. **예상 → 관찰 → 작은 테스트 → 내 말로 설명**을 한 번 반복한다. 코드 전체를 읽을 필요는 없다. 아래 해설은 마지막에 펼친다.

## 1. 예상부터 적기 — 3분

아래 좁은 경로만 읽는다.

- [ChatViewModel.kt](app/src/main/kotlin/dev/chatlab/ChatViewModel.kt): `send`의 `enqueue → submit`.
- [Outbox.kt](app/src/main/kotlin/dev/chatlab/Outbox.kt): `initialize`, `claimRetry`, `accept`의 SQL 조건.

코드 실행 전에 세 줄로 예상한다.

1. 로컬 SENDING 저장 뒤 서버가 append했지만 두 수락 알림을 받기 전에 앱을 죽이면, 새 실행의 상태는 무엇인가? 어떤 사실을 아직 모르는가?
2. 같은 ID 재시도 버튼을 두 번 누르면 DB claim·POST·서버 논리 메시지는 각각 몇 번인가?
3. Bob이 Alice와 같은 UUID를 쓰면 같은 outbox 행인가? 본문이 다르면 어떻게 판단해야 하는가?

## 2. 내가 실행해 확인하기 — 7분

기존 서버와 debug APK, 관찰한 에뮬레이터 serial을 사용한다. ADB 여러 버전이 있으면 SDK platform-tools/adb를 선택한다. 아래 `emulator-5554`는 기존 검증의 관찰값이다. 다른 앱·AVD 데이터는 건드리지 않는다.

서버가 없다면 별도 터미널에서 `./gradlew :server:run`을 유지한다. ACK 프록시는 **내가 실행한 세션만** Ctrl+C 후 새로 시작한다. 프록시 한 실행의 첫 Alice POST 한 번만 유실된다.

```bash
./gradlew :server:ackLossProxy -Pscenario=both --console=plain
```

다른 터미널에서 실행한다.

```bash
adb devices -l
bash scripts/ack-loss-lab.sh both emulator-5554
```

Alice로 `study-one`을 한 번 보낸다. 프록시에 그 메시지의 `PROXY_UPSTREAM_ACCEPTED`가 나타나면 **8초 timeout 전에** 앱만 종료한다. 늦었다면 UNKNOWN 보존 실험이 되므로 결과를 그대로 기록하고 다음 프록시 실행에서 다시 해도 된다.

```bash
adb -s emulator-5554 shell am force-stop dev.chatlab
bash scripts/capture-outbox-db.sh emulator-5554 study-before
adb -s emulator-5554 reverse --remove tcp:8081
adb -s emulator-5554 shell am start -n dev.chatlab/.MainActivity --es ack_loss_lab both
```

서버 snapshot 없이 화면이 무엇을 복원하는지 관찰한다. ID 앞 8자리·본문·상태를 적는다. Bob으로 바꿨다가 Alice로 돌아가며 미확인 행의 소유자를 확인한다. 그 다음 앱을 다시 멈춰 DB를 캡처한다.

```bash
adb -s emulator-5554 shell am force-stop dev.chatlab
bash scripts/capture-outbox-db.sh emulator-5554 study-after
```

호스트 Python으로 두 snapshot의 실제 필드를 비교한다. DB/WAL은 이 앱이 멈춘 상태에서 함께 복사됐으며 `evidence`는 Git에서 제외된다.

```bash
python3 - <<'PY'
import sqlite3
for name in ('study-before', 'study-after'):
    with sqlite3.connect(f'evidence/outbox/{name}.db') as db:
        print(name, db.execute("""
            SELECT userId, roomId, clientMessageId, text, status, createdAtMillis, serverId
            FROM outbox WHERE text = 'study-one'
        """).fetchall())
PY
```

마지막으로 기본 연결을 복원한다. 서버에 이미 수락됐던 메시지는 snapshot으로 확인되는지 본다. 수동 retry를 누르지 않았는데 새 POST가 발생했는지도 로그에서 확인한다.

```bash
adb -s emulator-5554 reverse tcp:8080 tcp:8080
adb -s emulator-5554 shell am start -n dev.chatlab/.MainActivity
chat_pid=$(adb -s emulator-5554 shell pidof dev.chatlab)
adb -s emulator-5554 logcat -d --pid="$chat_pid" -s ChatLab:I
```

현재 실행 중인 Activity가 실험 모드에 남아 있다면 이 앱만 force-stop한 뒤 extra 없는 기본 명령으로 다시 시작한다. 실험을 끝내면 본인 프록시만 종료하고 reverse 8081만 제거한다. `pm clear`나 앱 삭제는 사용하지 않는다.

## 3. 작은 테스트를 내가 추가하기 — 4분

[OutboxDatabaseTest.kt](app/src/androidTest/kotlin/dev/chatlab/OutboxDatabaseTest.kt)에 테스트 하나를 작성한다. 기존 memory DB 준비/정리 패턴을 사용한다.

- Alice/demo의 UNKNOWN 행 하나를 넣는다.
- 같은 user·room·client ID지만 **다른 본문**의 수락 Message를 `store.accept`에 넘긴다.
- 반환 값과 원래 행의 ID·본문·상태를 검사한다.

예상 결과를 먼저 써 놓고 테스트를 실행한다. production SQL을 일부러 고치거나 테스트를 통과시키려고 기대값을 바꾸지 않는다.

```bash
./gradlew :app:connectedDebugAndroidTest
```

## 4. 내가 설명할 기준 — 2분

화면·DB·서버 history를 구분해서 아래를 설명할 수 있으면 이번 실습의 목적을 달성했다.

- SENDING 저장이 서버 도착을 증명하지 않는 이유.
- 새 프로세스가 UNKNOWN으로 바꿔도 자동 POST하지 않는 이유.
- 로컬 transaction이 서버 append를 rollback하지 못하는 이유.
- 같은 ID를 유지해도 서버 재시작 뒤에는 지금 중복 방지가 보장되지 않는 이유.

설명이 막히는 항목 하나를 골라 관찰값과 함께 질문한다. 다음 기능을 바로 구현하기보다 그 경계를 한 번 더 재현한다.

<details>
<summary>막힐 때만 펼치는 힌트</summary>

`OutboxEntry`의 primary key와 `claimRetry`의 UPDATE WHERE를 먼저 본다. status만 검사하는지, 사용자·방·serverId도 검사하는지 비교한다. `accept`에는 키 이외의 조건 하나가 더 있다. 프로세스 시작의 `recoverInterrupted`에는 POST 호출이 없다.

</details>

<details>
<summary>예상과 실제를 비교한 뒤 펼치는 해설</summary>

첫 경우 DB에는 SENDING이 남을 수 있고, 새 프로세스는 같은 행을 UNKNOWN으로 바꾼다. 서버가 이미 수락했는지는 서버 기록과 대조해야 한다. 같은 프로세스의 중복 retry는 한 claim만 성공하므로 정상 상황에서는 POST 한 번이다. 네트워크 재시도 횟수와 서버 논리 메시지 수는 별개이며, 같은 ID를 서버가 이미 수락했다면 200으로 기존 결과가 돌아온다.

Bob의 같은 UUID는 다른 키다. 같은 계정·방·ID에 다른 본문을 붙여 수락시키려는 작은 테스트는 갱신 0이며 원래 UNKNOWN 행을 유지해야 한다. SQL의 text 조건이 이 판단을 지킨다. 이 조건만으로 모든 클라이언트 충돌 상황을 해결한 것은 아니며 서버의 409 계약도 함께 필요하다.

정상 종료의 정리 코드는 hard kill에서 실행되지 않는다. DB 복구 후 server snapshot이 수락을 확인하면 SENT가 되지만, 서버 메모리까지 재시작됐다면 같은 ID도 새로운 수락을 만들 수 있다. 로컬 Room 영속성은 서버의 영속성을 대신하지 않는다.

</details>
