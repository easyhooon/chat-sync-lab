# 15분 실습: 늦은 snapshot이 무엇을 뜻하는가

이번 앱은 메시지를 Room에서만 읽습니다. 첫 실습은 더 많은 기능을 만드는 대신 수신 순서를 바꿔도 저장 결과가 같은지 설명하는 것입니다.

## 1. 실행 전에 예상 적기 — 3분

1. HTTP history에는 #1만 있고, 응답이 도착하기 전에 WebSocket으로 #2를 받았습니다. 늦은 history를 저장하면 #2는 남아야 할까요?
2. 서버를 재시작하면 빈 snapshot이 오고 다음 메시지는 #1입니다. 기기에 저장된 이전 #1과 같은 메시지일까요?
3. Alice가 받은 기록을 저장한 뒤 서버를 끄고 Bob으로 바꾸면 무엇이 보여야 할까요?

각 질문에 화면 행 수와 DB 키를 먼저 적으세요. 정답을 보기 전에 아래 실험을 합니다.

## 2. 직접 확인 — 7분

[README 실행 절차](README.md#실행)로 서버와 Android를 연결합니다. 실제 `adb devices -l`의 serial을 사용하세요. Bob peer로 왕복하거나 Alice에서 메시지를 한 개 보냅니다.

- 채팅 앱만 `adb -s <serial> shell am force-stop dev.chatlab`으로 종료합니다. `pm clear`나 앱 삭제를 사용하지 않습니다.
- 이 프로젝트 서버 터미널에서 Ctrl-C로 서버만 종료합니다. 앱을 다시 실행합니다. 연결은 끊겼지만 기기에 저장한 메시지는 남는지 관찰합니다.
- Bob 신원으로 바꿉니다. Bob이 이전에 접속하지 않았다면 그 계정의 캐시는 비어 있습니다. Alice로 돌아오면 다시 보여야 합니다.
- 서버를 다시 실행하고 앱에서 다시 연결합니다. 빈 새 snapshot 뒤에도 과거 기록이 남는지 확인합니다. 새 메시지의 #1 옆 서버 실행 ID가 이전 것과 다른지 비교합니다.

캐시는 각 계정이 **관찰한 기록**입니다. 방 접근 권한은 서버가 검사하지만 테스트 신원 전환은 실제 로그인/보안 기능이 아닙니다. 기기 캐시가 남아 있다고 서버가 영속 저장한 것은 아닙니다.

## 3. 내가 테스트 하나 추가 — 5분

[MessageCacheTest.kt](app/src/androidTest/kotlin/dev/chatlab/MessageCacheTest.kt)에 테스트 하나를 직접 작성하세요. `#2 실시간 수신 → #1만 있는 과거 snapshot → #2 반복 이벤트` 순서에서 최종 행이 #1/#2 두 개인지 검사합니다. 이미 있는 테스트를 먼저 실행해 기준을 확인한 뒤 본인 테스트만 실행해도 됩니다.

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.chatlab.MessageCacheTest
```

`MessageCacheStore.importSnapshot`, `importInTransaction`, `MessageDao.observe` 세 곳만 읽고 “삭제가 어디에 없는지”, “중복이 어느 키로 막히는지”, “outbox 수락이 어느 transaction에 있는지”를 설명해 보세요. 테스트 도구는 종료 시 대상 APK를 제거할 수 있으므로 수동 데모는 테스트 종료 후 APK를 설치해 실행하세요.

<details><summary>막히면 힌트</summary>

새 임시 DB와 `MessageCacheStore`를 만듭니다. `importMessage`로 #2, `importSnapshot`으로 #1, `importMessage`로 #2를 넣고 `observe("alice", "demo").first()`의 sequence 목록과 size를 검사합니다. 운영 DB를 지우거나 구현을 망가뜨릴 필요가 없습니다.

</details>

<details><summary>실험 뒤 해설</summary>

snapshot은 서버가 그 순간 가진 목록입니다. 수신 캐시에 추가하되 없는 행을 삭제하지 않으므로 늦게 도착한 #1 조회가 먼저 받은 #2를 지우지 않습니다. 같은 실행 UUID/서버 ID의 동일 이벤트는 한 행이고, 다른 본문은 transaction 실패입니다.

서버 실행 UUID는 서버가 메모리 기록을 새로 시작했음을 구분합니다. 따라서 실행 A의 #1과 실행 B의 #1은 별개입니다. 화면은 실행을 처음 관찰한 그룹 순서와 그룹 내 sequence 순서만 보장합니다. 두 실행을 가로지르는 전역 시간순은 아닙니다.

Alice/Bob 캐시의 ownerId는 senderId와 다릅니다. Bob이 보낸 메시지도 Alice 연결에서 받았다면 Alice의 캐시에 저장됩니다. Bob 화면에 표시하려면 Bob 연결에서 받은 복사본이 필요합니다.

</details>
