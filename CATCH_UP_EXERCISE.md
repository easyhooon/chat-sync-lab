# 15분: 높은 번호를 받았는데 왜 복구가 끝나지 않을까

빈 로컬 서버와 관찰한 Android serial 하나를 사용한다. 기본 앱은 Firebase 등록을 실행하지 않는다. [실행](README.md#실행)을 먼저 준비한다. 이 프록시는 로컬 테스트 코드다.

## 실행 전 예상하기

서버에85개가 있다. 앱 첫 최신 페이지는 #66–#85다. 이후 소켓이 닫힌 동안120개가 더 추가되어 총205개가 된다.

1. 첫 base/contiguous/target은 얼마인가?
2. 복귀 최신 #186–#205가 저장됐을 때 max(sequence)와 contiguous는 각각 얼마인가?
3. after85의 #86–#105는 commit했고 다음 after105는503이다. 앱 프로세스를 종료했다가 열면 어느 번호부터 요청해야 할까?
4. 재개 HTTP 중 WS #206이 먼저 도착하면 target과 contiguous가 즉시 같아져도 될까?
5. 완료 후 #1–#65도 자동으로 저장돼 있어야 할까? before 버튼은 무엇을 읽을까?

## 로컬 재현

서버를 시작하고 빈 기록인지 확인한 뒤 앱을 열기 전에 seed한다. 스크립트는 데이터를 삭제하지 않는다.

```bash
python3 scripts/paging-fixture.py seed --count 85
# 다른 터미널: 두 번째 after 응답만503, 정상 after는1.5초 지연
./gradlew :server:ackLossProxy -Pscenario=catch-up
adb -s emulator-5554 reverse tcp:8080 tcp:8080
adb -s emulator-5554 reverse tcp:8081 tcp:8081
adb -s emulator-5554 shell am start -n dev.chatlab/.MainActivity --es ack_loss_lab catch-up
```

첫 연결의20개가 보이면 홈으로 이동한다. `PROCESS_BACKGROUND socketStopped` 로그를 확인한 후120개를 추가한다.

```bash
python3 scripts/paging-fixture.py append-batch --count 120 --text missed
adb -s emulator-5554 shell am start -n dev.chatlab/.MainActivity --es ack_loss_lab catch-up
adb -s emulator-5554 logcat -d -s ChatLab:I
```

첫 after commit 후 누락 복구 오류/재시도 버튼이 보인다. 기존 최신 기록은 유지된다. 자신의 앱만 force-stop해 partial DB를 캡처하면 base65/contiguous105/target205이고 max(sequence)=205다.

```bash
adb -s emulator-5554 shell am force-stop dev.chatlab
CHAT_EVIDENCE_GROUP=catch-up bash scripts/capture-outbox-db.sh emulator-5554 partial
adb -s emulator-5554 shell am start -n dev.chatlab/.MainActivity --es ack_loss_lab catch-up
# 새 after105 요청 직후 다른 터미널에서:
python3 scripts/paging-fixture.py append --text live-during-after
```

새 프로세스는 after105부터 재개한다. 최종 캐시는 #66–#206의141개이고 confirmed=target=206이다. #1–#65는 before 과거 탐색 범위로 남는다. before 탐색 키 자체는186이므로 첫 before 응답은 이미 캐시한 #166–#185다. 중복 병합 후 키를 따라 더 과거로 진행하면 #1–#65를 가져온다. after가 before 키를 임의로 소비하지 않기 때문에 HTTP overlap이 생길 수 있다. 이 서버가 재시작하면 이전 실행의 아직 못 받은 기록은 복구할 수 없다.

멈춘 DB의 `sync_cursors`와 `cached_messages`를 조회해 예상과 대조한다. 실제 이 Mac 증거는 다음으로 검증한다.

```bash
python3 scripts/verify-catch-up-evidence.py
```

## 직접 추가할 테스트 한 가지

기존 자동 검사는 높은 이벤트/Push가 먼저 도착하는 경우, 역순·중복·여러 페이지·중간 HTTP 실패/DB 재열기·계정/실행 격리·v3 migration·transaction rollback을 확인한다.

다음은 별도로 `ForegroundChatSession`의 실제 lifecycle 경계를 시험해 보자. after 응답을 임시 gate로 멈춘 상태에서 앱을 배경으로 보낸다. commit 전 취소됐다면 cursor가 그대로인지 확인한다. 복귀 후 같은 지점부터 요청하게 한다. 이번에는 gate를 Room commit 직후로 옮겨 취소한다. 두 경우 모두 진행 표시가 현재 generation에만 반영되고 유효하게 commit한 원래 scope의 본문은 남는지 예상부터 적는다. 단순히 DAO를 직접 호출하는 검사와 coroutine 취소/화면 generation을 검증하는 차이를 설명한다.
