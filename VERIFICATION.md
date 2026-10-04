# 실제 검증 기록

첫 로컬 검증: 2026-10-04. JDK 21 / Gradle 9.2.1 / Kotlin 2.3.20 / AGP 9.0.1 / Ktor 3.4.3. compile/target SDK 36, 실행 AVD는 Pixel_8a(API 34 ARM64), RAM 2048MB/2 cores, 읽기 전용·스냅샷 미저장.

## 통과

| 검사 | 실제 결과 |
|---|---|
| `:server:test` | 6 tests, 0 failures/errors/skips |
| `:app:testDebugUnitTest` | 7 tests, 0 failures/errors/skips |
| `:app:assembleDebug` | APK 생성 성공 |
| `:server:installDist` | 실행 가능한 서버 배포 디렉터리 생성 |
| `:app:lintDebug` | 0 errors, 12 warnings |
| 로컬 서버 실행 | `/health` 성공; listen 주소 `127.0.0.1:8080` 관찰 |
| Android 실행 | `emulator-5554`, `sys.boot_completed=1`, APK 설치 Success, 화면 연결됨 |
| 실시간 왕복 | Android Alice POST → Bob Ktor peer WS 수신 → Bob POST → Android WS 수신; history와 양쪽 로그의 ID 일치 |
| 같은 ID 재전송 실험 | `201 → 200 → 409`, 첫/재전송의 서버 ID·sequence 동일, history는 1개만 증가 |
| 연결 오류·복구 | ADB reverse 제거 후 새 앱만 재시작: 연결 끊김·오류 안내·전송 비활성화 관찰. reverse 복구 + 다시 연결: 서버 snapshot 6개 복원, 중복 행 없음 |

서버 테스트는 HTTP/WS 왕복·history·동일 키 재전송·409 충돌·잘못된 입력·HTTP 권한·실제 loopback WS handshake(멤버101/비멤버403/알 수 없는 신원401)·40개 동시 전송 순서·snapshot 구독 경계를 확인한다.

Android 테스트는 REST/WS 중복 병합, 늦은 HTTP 실패가 SENT를 되돌리지 않음, snapshot의 UNKNOWN 대조, 서버 새 snapshot에서 이전 프로세스 기록 제거, 다른 sender의 동일 client ID 분리, 수락 뒤 오류 문구 억제, 늦은 수락으로 해당 메시지 오류 해제를 확인한다.

## 실패 후 해결

- 첫 권한 테스트는 수동 Upgrade 헤더를 Ktor 테스트 엔진에 넣어 `UnsafeHeaderException`이 났다. 실제 WS Client 검사와 실제 Netty loopback TCP handshake 검사로 수정했다. 일반 GET으로 WS 전용 경로에 접근하면 404일 수 있으므로 접속 프로토콜을 구분했다.
- 테스트 엔진은 거절된 WS 접속에 상태 코드를 포함하지 않는 일반 접속 실패를 돌려줬다. 401/403 확인은 실제 TCP handshake 검사로 보강했다.
- ADB 최초 조회는 샌드박스의 로컬 포트 제한으로 실패했다. 승인된 개발 실행에서 SDK ADB를 사용해 조회·실행했다.
- 초기 AVD의 System UI ANR 팝업은 `Wait`를 선택해 해소했다. 다른 앱은 종료하지 않았다. 입력 이벤트 지연 때문에 첫 Bob peer는 2분 timeout으로 종료됐으며, 입력과 전송을 분리한 다음 실행에서 왕복을 확인했다.
- 읽기 전용 Codex 리뷰에서 WS 수락 후 늦은 POST 실패의 오류 문구 모순과 Bob peer의 “다음 프레임이 반드시 echo” 가정을 찾았다. 상태 reducer와 ID 기반 echo 대기로 고치고 검사·빌드를 다시 통과시켰다.

## 첫 단위 종료 시 한계

- Lint 경고 12개: 이전 target SDK 1개, 의존성 최신 버전 안내 9개, backup 설정 1개, 앱 아이콘 1개. 로컬 첫 실행에는 오류가 없지만 제품화 전에 정리해야 한다. 경고를 숨기지 않았다.
- 두 에뮬레이터 동시 왕복·실기기·Android instrumentation UI 테스트는 미실행. 합의한 한 Android 세션 + 테스트 peer를 실제 사용했다.
- 자동 reconnect/backoff, 실제 무선망 단절, 영속 DB/outbox, 다중 서버, 장시간 부하·slow subscriber 스트레스는 미실행/미구현. 수락 알림 유실은 아래 두 번째 단위에서 로컬 테스트 프록시로 검증했다.
- `SENT`는 현재 프로세스 메모리 수락이다. 프로세스 재시작 뒤 기록·중복 키·sequence는 보존되지 않는다. 실제 인증은 없다.

## 증거

`evidence/verification.log`, 서버/Android JUnit XML, lint 보고서, `bob-client.log`, `android-round-trip.log`, `history.json`, `ui-round-trip.xml`, `round-trip.png`, `idempotency.log`를 로컬에 남긴다. 로그·스크린샷·실행 머신 설정은 공개 저장소에서 제외한다.

오류·복구 증거는 `ui-offline-final.xml`, `screen-final.png`(오류 화면), `ui-recovered.xml`, `recovered.png`이다. 처음 reverse만 제거했을 때 이미 열린 소켓은 유지됐다. 새 앱만 재시작한 뒤 오류를 확인했으며, 이는 **이미 진행 중인 POST의 응답 유실 실험은 아니다**.

로컬 증거가 있는 환경에서는 다음 명령으로 양쪽 로그·history·화면의 메시지 ID/텍스트와 중복 여부를 대조한다.

```bash
python3 scripts/verify-evidence.py
```

## 두 번째 단위: 수락 뒤 응답 유실 — 2026-10-04

최종 debug APK를 같은 `emulator-5554`에 다시 설치하고, 기존 메모리 서버 앞의 **별도 테스트 프록시**로 두 경우를 실제 실행했다. 기본 서버 API에는 실패 제어를 추가하지 않았다. 프록시는 `server/src/test`에 있고 명시 실행 때만 loopback 8081/8082를 연다. 앱의 실험 모드는 debug에서만 선택되며 기본/릴리스 연결은 8080이다.

| 검사 | 실제 결과 |
|---|---|
| `:server:test` | 기존 6 + 실제 Netty loopback 프록시 2 = 8 tests, failures/errors/skips 0 |
| `:app:testDebugUnitTest` | 11 tests, failures/errors/skips 0 |
| `:app:assembleDebug`, APK 설치 | 성공, 최종 APK로 두 경우 확인 |
| `:app:lintDebug` | 0 errors, 12 warnings — 기존 경고 수 유지 |
| HTTP + WS 수락 알림 유실 | 실제 `SENDING → UNKNOWN`; 앱이 UNKNOWN일 때 서버 history에는 이미 수락 메시지가 있음 |
| UNKNOWN 같은 ID 수동 재시도 | HTTP `201 → 200`, history `7 → 8 → 8`, 원래 서버 ID·sequence 유지, 화면 한 행이 SENT로 변경 |
| HTTP만 유실, WS 수락 도착 | WS로 SENT 후 8초 HTTP timeout; 로그 `resultingStatus=SENT`, UNKNOWN/오류/재시도 버튼 없음, history `8 → 9` |
| 실험 정리·기본 연결 복원 | 본 작업의 프록시만 종료, reverse 8081/8082만 제거. 기본 앱 재시작 후 8080 연결·9개 snapshot 복원; 8080만 listen |
| 로컬 증거 대조 스크립트 | `python3 scripts/verify-ack-loss-evidence.py` 통과 |

두 알림 유실의 최종 메시지는 client ID `1b0e7253-f4d4-43bc-872c-635538030632`, 서버 ID `2c745602-9387-42b4-ac93-8b28f91b093f`, sequence `8`이다. HTTP-only 비교는 client ID `901ca9ae-19e9-4d09-92cc-5c01042b0407`, 서버 ID `9cb4a659-d97b-4536-a6df-8919429a3542`, sequence `9`다. 모두 이 로컬 서버 프로세스의 테스트 데이터다.

프록시는 서버 수락 응답을 받은 **뒤** 앱의 HTTP 응답을 60초 지연시킨다. BOTH에서는 해당 Alice 메시지의 WS 이벤트도 숨긴다. 8초 timeout 이후 앱 기다림은 끝나지만 서버 append는 이미 완료돼 있다. 앱의 coroutine 취소가 서버의 완료된 작업을 rollback하는 관계는 아니다. WS를 받은 비교에서 SENT를 유지한 것도 같은 수락 근거의 차이를 보여준다.

통합 테스트는 실제 loopback 소켓에서 HTTP timeout, 이미 수락된 history, 동일 요청의 200/동일 Message, 추가 WS 이벤트 없음, 다른 sender가 같은 UUID를 사용해도 별도 메시지로 전달됨을 검사한다. 앱 단위 테스트는 자신의 연결된 UNKNOWN 행만 재시도 가능, ID·본문 유지, 이미 SENDING이면 재시도 불가, WS 수락과 늦은 HTTP 실패의 상태·오류 충돌 방지를 검사한다. ViewModel의 CAS 자체를 instrumentation으로 테스트하지는 않았다.

### 검토와 수정

- 별도 읽기 전용 검토에서 프록시의 이벤트 유실 조건에도 sender가 필요함을 확인했다. Alice의 ID만 숨기도록 고치고 Bob의 같은 UUID 전달 테스트를 추가했다.
- 통합 테스트 준비 중 예외가 나도 이미 시작한 서버·프록시·HTTP client를 정리하도록 자원 정리를 고쳤다.
- 첫 실제 UNKNOWN 화면에서 오류 문구·재시도 버튼이 추가되며 마지막 행 일부가 가려졌다. 마지막 행의 상태 변화에도 스크롤하게 수정하고 최종 APK에서 버튼이 스크롤 조작 없이 보이는 것을 확인했다.
- 로컬 실행 연결이 한 차례 잠깐 끊겨 관찰을 멈췄으나 다음 재조회에서 회복했고, 최종 화면·로그·history를 다시 확인했다. 초기 증거 대조 코드는 history 객체의 `messages` 필드를 빠뜨려 실패했다. 아래 최종 스크립트는 실제 JSON 구조를 읽어 통과했다.

### 재현과 증거

실행 절차는 [학습 안내](STUDY_GUIDE.md#직접-따라하기-두-알림-유실)에 있다. 프록시는 첫 Alice POST에 한 번만 주입한다. UNKNOWN 관찰 중 다시 연결하면 snapshot이 이미 수락을 확인하므로 그대로 foreground를 유지한다.

로컬 `evidence/ack-loss/`에 `verification.log`, `proxy-both-final.log`, `proxy-http-only.log`, `android-both-final.log`, `android-http-only.log`, `history-both-*.json`, `history-http-*.json`, `ui-both-*-final.xml`, `ui-http-after-timeout.xml`, 화면 PNG를 보관했다. 파일은 공개 저장소에서 제외한다. 빌드의 JUnit XML과 lint 보고서는 각 모듈 `build`에 있다.

```bash
python3 scripts/verify-ack-loss-evidence.py
```

두 번째 단위 결과는 한 Android 세션 + 로컬 테스트 프록시·서버 검증이다. 당시 두 Android 동시 실행, 실기기·instrumentation, release APK 빌드, 실제 무선망 단절, 앱/서버 재시작 동안의 영속 복구는 미실행이었다. proxy 선택의 release 차단은 `BuildConfig.DEBUG` 소스 분기로 확인했다. 아래 세 번째 단위에서 Room outbox와 앱 프로세스 재시작 복구를 추가했다. 서버 DB·자동 retry/reconnect는 여전히 없다.

## 세 번째 단위: Room outbox — 2026-10-05 (한국시간)

착수 시 local/remote main은 `a3af39c6923a523b6ada3f77249a8b98d290fb21`로 일치했고 clean이었다. 연결 기기·8080 서버가 없음을 확인한 뒤 이 프로젝트의 기존 loopback 서버와 Pixel_8a 한 개를 다시 실행했다. Java/Gradle/Kotlin/AGP는 유지하고 Room 2.8.5·KSP 2.3.10·Android test 의존성을 공식 프로젝트 저장소에서 사용했다. 새 시스템 도구·계정·서비스는 설치하지 않았다.

### 실제 통과한 검사

| 검사 | 결과 |
|---|---|
| `:server:test` | 기존 서버 6 + ACK 프록시 2 = 8 tests, failures/errors/skips 0 |
| `:app:testDebugUnitTest` | 14 tests, failures/errors/skips 0 |
| `:app:connectedDebugAndroidTest` | 실제 API 34 AVD의 Room DB 8 tests, failures/errors/skips 0 |
| Debug/instrumentation APK 빌드·설치 | 성공, 최종 구현 APK로 아래 화면·DB 검증 |
| `:app:lintDebug` | errors 0, warnings 15 (이전 12 + 새 빌드/의존성 버전 안내) |
| BOTH 수락 유실 | `OUTBOX_COMMITTED` 다음 POST; UNKNOWN→동일 ID 수동 retry→200/SENT, 서버 history 1→1, DB receipt 한 행 |
| HTTP-only 수락 유실 | WS로 수락 후 HTTP timeout; UI·DB 모두 SENT, UNKNOWN/재시도 버튼 없음 |
| UNKNOWN 프로세스 재시작 | PID `8065→8349`; snapshot 없는 offline 화면에 같은 ID·본문·UNKNOWN 복구; DB의 모든 필드 동일 |
| 실제 계정 전환 | Bob에는 Alice UNKNOWN 없음; Alice로 돌아오면 한 행 복원; DB 변경 없음 |
| 재연결·수동 재시도 | reconnect만으로 POST하지 않음; 서버 2개 그대로. 버튼을 눌러 원래 ID·본문·계정·방·생성 시각을 유지해 201/SENT, 서버 3개 |
| SENDING 중 hard kill | 서버 수락 뒤 앱 PID 9135 종료, 멈춘 DB에 SENDING; 새 실행 로그 recoveredSending=1, 같은 필드의 UNKNOWN 복구 |
| 재시작 뒤 snapshot 대조 | 서버의 수락 기록으로 SENT 갱신, 신규 POST 없이 history 4개 유지 |
| 기본 상태 복원 | 최종 화면 4개 서버 수락 행·DB SENT 영수증 일치, 프록시 종료, reverse는 8080만 유지 |
| 증거 대조·스크립트 검사 | `verify-outbox-evidence.py` 5가지 PASS, capture 스크립트 bash 문법·git diff 검사 PASS |

Room instrumentation 8개는 파일 DB 재열기/같은 필드 보존/SENDING 복구, 계정·방·ID 복합키 격리, 24개 동시 retry 중 단일 claim, 20개 메시지의 중복 수락·늦은 실패 경쟁, process-store 초기화 반복 시 살아 있는 SENDING 유지, snapshot의 자신의 행만 대조, 다른 DB 연결에서 enqueue commit 확인, 취소된 retry transaction rollback을 검사했다. 임시 DB만 제거했고 실제 앱 데이터에는 `pm clear`/삭제를 사용하지 않았다.

수동 복구 메시지 `persist3`의 client ID는 `0041aefa-9315-4ac3-adf3-31f526ec98d8`, 서버 ID는 `beaa6e5e-fc23-4954-8529-0570f71fe64c`, sequence는 3이다. hard kill의 `kill3`는 client ID `e69ac4b7-69d4-478e-b2fe-b6e6f544b957`, 서버 ID `c6b63416-4e9e-4722-9bdd-e5809cb3ddd9`, sequence 4다. 모두 로컬 테스트 데이터다.

### 구현 후 확인한 경계

- 로컬 commit 전에는 POST가 없다. 저장 실패 시 입력을 유지한다. 로컬 transaction과 원격 append는 하나의 transaction이 아니다.
- DB failure 갱신은 정확한 계정·방·ID의 SENDING/serverId 없음만 대상으로 한다. SENT receipt를 늦은 HTTP 오류가 되돌릴 수 없다.
- 계정 변경 뒤 이전 HTTP 결과도 원래 scope의 DB에 저장한다. generation 검사가 새 계정 화면 갱신을 막는다. Room의 오래된 UNKNOWN emission도 현재 수락 행을 되돌리거나 중복 행을 만들지 않는다.
- 전체 SENDING 초기화는 프로세스당 한 번이다. reconnect/계정 전환은 살아 있는 요청을 복구 대상으로 취급하지 않는다. 정상 ViewModel 취소는 guarded UNKNOWN 정리를 시도하지만 hard kill은 다음 실행의 복구가 담당한다.
- 완료 receipt는 DB에 유지하되 서버 snapshot에 없는 과거 기록을 현재 history로 만들지 않는다. 현재 수신 전체 캐시는 아직 Room에 저장하지 않는다.

### 검증 자동화에서 실패 후 해결

실제 hard-kill 검증의 첫 cold start에서 UIAutomator가 `null root`를 반환해 전송을 시작하지 못했다. 초기 묶음 명령이 실패 뒤에도 계속돼 유효하지 않은 캡처가 생겼다. 실패 즉시 중단하도록 바꾸고, 안정된 연결 화면을 먼저 확인한 뒤 별도의 `*-final` 증거로 다시 검증했다. 마지막 기본 모드의 첫 XML도 일부 노드만 있어 증거 대조가 실패했으며, 안정된 현재 화면을 재관찰해 전체 대조를 통과시켰다. 이 실패한 초기 캡처를 통과 근거로 사용하지 않았다.

### 근거와 남은 범위

`evidence/outbox/verification.log`, `device-verification.log`, proxy/app 로그, UI XML/PNG, history JSON, 멈춘 프로세스의 DB/WAL snapshot을 로컬에 보관했다. JUnit XML·lint 보고서는 각 모듈 `build`에 있다. 실제 DB와 증거·로컬 SDK 설정·빌드 결과는 공개 Git에서 제외한다. `app/schemas/dev.chatlab.OutboxDatabase/1.json`은 DB 내용이 없는 버전 1 schema이며 소스와 함께 관리한다.

```bash
python3 scripts/verify-outbox-evidence.py
```

실기기·두 Android 동시 실행·Android UI instrumentation·release 빌드·실제 무선망 단절·디스크 용량 부족/손상 주입·DB migration 변경 검사는 미실행이다. 새 프로세스/동일 DB와 실제 Room instrumentation은 검증했다. selected 테스트 신원은 기존처럼 Alice로 시작하며, 계정별 outbox 기록은 보존된다. 서버 DB가 없어 서버 재시작 뒤 idempotency 기록·sequence가 사라지는 한계는 그대로다.

[15–20분 실습](OUTBOX_EXERCISE.md)은 예상부터 적고 hard kill을 직접 재현한 뒤 본문 불일치 수락 테스트 하나를 사용자가 작성하도록 구성했다. 힌트·해설은 접어 두었고 학습용으로 구현을 망가뜨리지 않았다. 이후 구현을 자동 진행하지 않는다. 다음 한 단위는 수신 기록도 Room 기준으로 통합하는 것이며, 그 다음 서버 cursor 과거 페이징·실시간 중복/누락/스크롤 앵커를 검증한다. Paging3/RemoteMediator는 이번에 추가하지 않았다.
