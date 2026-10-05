# 15분 실습: 과거 페이지를 보는 동안 새 메시지가 오면

## 먼저 예상 — 3분

서버에 #1–#85가 있고 첫 페이지는 #66–#85입니다. before cursor는 #66을 배제합니다.

1. #86이 새로 와도 다음 과거 페이지는 #46–#65일까요, #47–#66일까요?
2. 과거 조회가 503으로 실패했다면 DB cursor와 이미 보던 메시지는 무엇이 바뀌어야 할까요?
3. #70을 읽는 중 #86이 오면 화면은 #86으로 움직여야 할까요? 같은 메시지의 stable key와 offset은 어떻게 되어야 할까요?

행 개수·sequence 범위·스크롤 위치를 먼저 적고 아래를 실행하세요.

## 실패를 직접 보고 재시도 — 7분

[README](README.md#실행)의 서버/AVD와 debug APK를 준비합니다. 첫 bootstrap 전에 채팅 앱만 멈추고 **빈 로컬 서버**에 기록을 만듭니다. seed는 기존 기록이 있으면 중단하며 데이터를 삭제하지 않습니다.

```bash
adb -s <serial> shell am force-stop dev.chatlab
python3 scripts/paging-fixture.py seed --count 85
python3 scripts/paging-fixture.py history
```

테스트 proxy를 다른 터미널에서 실행한 뒤 앱을 엽니다. 일반 서버에는 실패 제어 API가 없습니다.

```bash
./gradlew :server:ackLossProxy -Pscenario=page-failure --console=plain
adb -s <serial> reverse tcp:8081 tcp:8081
adb -s <serial> shell am start -n dev.chatlab/.MainActivity --es ack_loss_lab page-failure
```

이 모드는 debug APK에서만 선택됩니다. 첫 before 조회는 한 번 503을 반환하고 이후 조회는 성공합니다. WS와 POST는 계속 전달합니다. 나중 과거 응답을 잠깐 늦추므로 새 메시지와 겹침을 관찰할 수 있습니다.

최신 페이지에 #66–#85가 보이는지 확인한 뒤 #83 같은 과거 위치로 조금 스크롤합니다. 그 위치에서 아래 명령으로 새 메시지를 보냅니다. 같은 key/offset에 머무는지 먼저 확인하세요.

```bash
python3 scripts/paging-fixture.py append --text live-anchor-086
```

“과거 더 보기”를 눌러 첫 503을 만든 뒤 재시도하세요. 재시도 직후 별도 터미널에서 `python3 scripts/paging-fixture.py append --text live-during-older-087`을 실행하면 과거 요청 중 live 수신을 볼 수 있습니다. “과거 조회 실패”에서 기존 기록을 읽을 수 있는지, “과거 조회 재시도”를 누르면 **동일 before**로 다시 조회하는지 proxy 로그와 앱 로그를 비교합니다. 본문의 private 데이터가 없는 로컬 fixture만 사용하세요.

서버를 종료하고 채팅 앱만 force-stop한 뒤 다시 실행하면 다운로드한 과거 캐시가 남는지 확인합니다. `pm clear`/앱 삭제는 이 실험이 아닙니다. 실험 뒤 proxy만 종료하고 8081 reverse를 제거한 뒤 기본 앱으로 다시 실행합니다.

## 테스트 하나 직접 추가 — 5분

[PagingDatabaseTest.kt](app/src/androidTest/kotlin/dev/chatlab/PagingDatabaseTest.kt)에 테스트를 추가합니다.

- run-A의 최신 #61–#80을 저장한 뒤 과거 요청 key를 보관합니다.
- run-B의 최신 #1–#5를 저장해 서버가 재시작한 상황을 만듭니다.
- run-A의 늦은 과거 #41–#60 응답을 원래 request로 전달합니다.
- run-B의 key/다섯 행은 그대로이며 run-A에는 과거가 추가되는지, Paging 정렬이 실행 그룹 ordinal을 유지하는지 검사합니다.
- 새 실행의 sequence가 작다고 두 실행을 섞거나 이전 캐시를 삭제하면 안 됩니다.

```bash
ANDROID_SERIAL=<serial> ./gradlew -Pandroid.injected.device.serial=<serial> \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.chatlab.PagingDatabaseTest \
  :app:connectedDebugAndroidTest
```

테스트 도구는 대상 APK를 정리할 수 있으므로 수동 데모는 테스트를 끝낸 뒤 APK를 설치해 실행하세요. 여러 AVD가 연결돼 있으면 반드시 serial을 제한합니다.

<details><summary>힌트</summary>

Room의 위치 기반 Int key는 UI가 DB를 몇 행씩 읽을지 정합니다. 서버의 before cursor는 네트워크의 과거 범위를 정합니다. 둘은 다릅니다. `HistoryKey`와 캐시 모두 serverInstanceId를 키에 포함합니다. 늦은 응답은 원래 요청의 실행으로 저장하며 현재 화면의 실행을 추측하지 않습니다. Paging SQL은 실행 그룹 ordinal을 sequence보다 먼저 정렬합니다.

</details>

<details><summary>실험 뒤 해설</summary>

새 append는 높은 sequence를 만들지만 before #66은 여전히 sequence &lt;66입니다. offset 기반 서버 페이지처럼 새 append 때문에 경계가 밀리지 않습니다. 503이면 저장 transaction을 시작하지 않으므로 key는 그대로고 retry가 같은 범위를 읽습니다.

과거를 읽는 동안 새 수신은 DB에 들어가도 stable key/offset을 유지합니다. 사용자가 최신 끝을 따라갈 때만 최신 위치로 이동합니다. `before` 과거 탐색으로 끝까지 읽는 것과, 전경 복귀 때 `after`로 누락을 보충하는 것은 다른 작업입니다. 배경 동안 최신 20개보다 많이 왔다면 현재 bootstrap만으로 전부 받았다고 말할 수 없습니다.

</details>

이번 Mac의 저장된 실행 증거는 `python3 scripts/verify-paging-evidence.py`로 대조합니다. 이 스크립트는 해당 기록(#1–#91·로컬 캡처)을 읽을 뿐 서버/기기를 시작하거나 수정하지 않습니다. 전체 실제 결과와 실패 후 재검사는 [VERIFICATION](VERIFICATION.md)에 있습니다.
