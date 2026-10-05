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

## 네 번째 단위: 수신 캐시와 DB 단일 읽기 — 2026-10-05 (한국시간)

착수 checkout은 `c668d504752c485b66764099f81155bd0abb8fe7`이며 local/remote main 일치·clean이었다. 기존 도구와 의존성을 유지했다. 새 시스템 도구나 서버 DB는 추가하지 않았다. 서버 실행 UUID를 HTTP history/WS snapshot/각 Message에 추가했으므로 앱과 서버를 함께 빌드한다. DB v2의 수신 캐시와 outbox는 한 DB·transaction으로 갱신하며, UI 메시지 목록은 한 SQL Flow만 읽는다.

| 검사 | 실제 결과 |
|---|---|
| `:server:test` | HTTP/WS·권한·idempotency·ACK 유실 기존 8 + 실행 namespace 1 = 9, failures/errors/skips 0 |
| `:app:testDebugUnitTest` | 현재 DB projection/오류 해제/수동 retry 화면 정책 6, failures/errors/skips 0 |
| `:app:connectedDebugAndroidTest` | 기존 outbox 8 + 수신 캐시/migration 13 = 21, failures/errors/skips 0 |
| `:server:installDist`, debug/test APK | 실제 빌드·설치 성공 |
| `:app:lintDebug` | errors 0, warnings 15 |
| 과거 조회 + 실시간 수신 | 접속 전 Bob `history-cache4` #1 복원, 연결 후 `live-cache4` #2 수신; HTTP history·WS 이벤트·DB·UI 일치 |
| 동일 요청 반복 | `live-cache4` HTTP 201→200, 같은 서버 ID/sequence, 캐시·history 각 두 행 유지 |
| 서버 종료 + 새 앱 프로세스 | PID 7813→8186. 서버 종료 및 reverse 제거 상태에서 같은 DB의 Alice 두 수신 행 복원 |
| 오프라인 계정 전환 | Bob 캐시는 비어 있음; Alice 복귀 시 두 행 표시. online/offline DB 모든 캐시 필드 동일 |
| 서버 재시작 | 새 실행 빈 history/snapshot 뒤 기존 두 행 유지. 새 Bob #1은 다른 실행 UUID로 세 번째 캐시 행 |
| 실제 Android/Bob 왕복 | Alice `own-cache4` POST→Bob WS 수신→Bob HTTP 응답→Android WS 수신. `ROUND_TRIP_PASS historyCount=3` |
| 최종 DB 대조 | 캐시 5개(이전 실행 2 + 현재 3), 실행 그룹 ordinal 1/2, 서로 다른 UI key 5개, Alice SENT outbox 1개. 서버는 현재 3개 |
| 증거/스크립트/변경 검사 | `verify-cache-evidence.py` 3개 PASS, capture script bash 문법·git diff 검사 PASS |

Room 캐시 검사 13개는 실제 v1 schema JSON→v2 migration의 UNKNOWN/SENDING/SENT 보존·추가 nullable 실행 ID·schema 검증, 늦은 과거 snapshot, 12개 동시 snapshot/반복 이벤트, 빈 새 실행 snapshot/sequence·서버 ID 재사용, 계정·방/늦은 원래 계정 결과 격리, cache+receipt 원자적 수락·같은 UI key·늦은 실패, snapshot 충돌 transaction rollback, 잘못된 room/namespace 차단, pending 정렬/다른 sender 같은 client ID, 파일 DB 재열기, 실제 Flow의 pending/accepted 단일 행 전환, 새 서버 수락의 과거 SENT 보존, 논리 ID/sequence unique 충돌 검사를 포함한다. 이전 메모리 병합 함수를 제거하면서 해당 중복·순서 검증은 실제 DB 검사로 옮겼고, 현재 JVM 검사는 transient 화면 상태만 검사한다.

### 검토 관점과 경계

구현 이후 데이터 흐름·접근 검사·중복 키·오류 표시를 별도로 다시 읽었다. 수신 캐시 owner와 sender를 분리했고, 캐시 삽입과 자신의 수락 receipt를 같은 transaction으로 묶었다. 화면 네트워크 콜백의 목록 변경과 기존 hybrid 병합은 제거했다. immutable 동일 키 충돌·본문/sequence 변경은 덮어쓰지 않으며 transaction 실패를 표시한다. 새 서버 실행은 과거 receipt를 바꾸지 않는다. HTTP timeout/DB 저장 실패 시에도 결과를 섣불리 SENT로 표시하지 않는다. Room Flow의 accepted row만 해당 송신 오류를 해제하고 연결/저장 오류는 따로 유지한다.

현재 정렬은 클라이언트의 실행 그룹 첫 관찰 순서 + 그룹 안 sequence이며 전역 시간순이 아니다. snapshot에서 사라진 캐시를 삭제하지 않으므로 오래된 실행 기록도 남는다. 이는 기기가 관찰한 기록이며 완전한 서버 history나 서버 영속성 보장은 아니다. 실제 인증은 여전히 local X-Test-User이고 HTTP/WS 접근 검사는 기존 실제 loopback 테스트로 검증했다.

### 실패 후 해결

- 새 테스트에서 RoomDatabase가 Closeable이라는 가정으로 컴파일이 실패했다. 테스트 전용 try/finally close helper로 고친 뒤 전체 빌드·최종 21 DB 검사를 통과했다.
- 최종 instrumentation과 초기 수동 캡처가 겹쳤으며 테스트 도구 종료 시 대상 APK가 제거돼 offline start에서 Activity 없음이 발생했다. 테스트 종료 후 APK 재설치→새 데모 fixture→online/offline/restart/final DB 캡처를 순서대로 다시 수집했다. 통과 근거는 이 최종 캡처다. 실제 APK 제거는 테스트 harness 동작이며 작업에서 `pm clear`를 실행하지 않았다.
- cold start 직후 pidof가 아직 빈 값을 반환해 묶음 검증을 중단했다. 화면을 먼저 관찰한 뒤 새 PID를 확인했다. Bob sender 라벨과 신원 Chip의 텍스트가 같아 초기 자동 tap도 중단됐고, 신원 Chip을 구분해 재실행했다. 이 중단을 통과로 계산하지 않았다.

### 재현 가능한 자료와 미실행

실행 방법은 [README](README.md#실행), 예측·오프라인 재실행·사용자가 직접 추가할 테스트는 [15분 실습](CACHE_EXERCISE.md)에 있다. 이 작업 서버와 읽기 전용 Pixel_8a는 검증 후 종료한다. 기기/서버는 다음 실행에서 새 fixture로 재현하며, 이번 실제 UI XML/PNG·PID·DB/WAL·로그는 `evidence/cache/`에 남는다. 읽기 전용 AVD의 임시 앱 데이터를 다음 AVD 실행의 영속 데이터로 취급하지 않는다. 공개 Git에는 DB 내용·실행 로그·machine SDK 설정을 넣지 않는다. schema1/2 JSON만 관리한다.

```bash
python3 scripts/verify-cache-evidence.py
# 기기 앱을 종료한 뒤 이번 캐시 DB만 캡처할 때:
CHAT_EVIDENCE_GROUP=cache bash scripts/capture-outbox-db.sh <serial> <label>
```

`build-verification-final.log`, `room-verification-final.log`, `evidence-verification.log`와 모듈 JUnit XML/lint 보고서가 검사 근거다. final DB와 현재 서버 history의 개수가 다른 이유는 이전 서버 실행의 두 캐시 행을 의도대로 보존했기 때문이다.

실기기·두 Android 동시 실행·UI instrumentation·release 빌드·디스크 손상/용량 부족 주입·ViewModel generation 경쟁 자동 검사는 미실행이다. 실제 한 Android 세션 + Ktor Bob WS peer를 사용했다. ACK 프록시 통합과 기존 outbox DB 검사는 재실행했지만 이번 최종 APK로 BOTH/HTTP-only 수동 유실 데모는 반복하지 않았다. 자동 retry/reconnect·서버 DB·cursor 과거 페이징·누락 보충은 추가하지 않았다. 다음 한 가지는 실행 UUID를 포함하는 cursor의 과거 조회/실시간 중복·누락 경계를 직접 검증하는 것이다.

## 다섯 번째 단위: 과거 cursor·Room Paging·전경 소켓 — 2026-10-06 (한국시간)

착수 checkout은 `d9241a25d2de6603c560b240b9d76aca9ed8ff58`이며 local/remote main 일치·clean이었다. 설치된 도구를 유지하고 공식 프로젝트 의존성에 Paging 3.5.1, Room Paging 2.8.5, lifecycle-process 2.9.4를 추가했다. 새 시스템 도구·Firebase 설정·계정·서비스는 없다. 서버/앱을 함께 빌드해야 하는 bounded page API 변경이며 DB v3는 v2 캐시와 outbox를 보존한다.

| 검사 | 최종 실제 결과 |
|---|---|
| 서버 통합/unit | 13 tests, failures/errors/skips 0. 기존 HTTP/WS·권한·idempotency·ACK 유실 + exclusive page·bootstrap/live lock·cursor room/run 오류·503 동일 cursor retry 중 live 수신 |
| Android JVM | 6 tests, failures/errors/skips 0 |
| Android API 34 instrumentation | outbox 8 + 캐시 13 + Paging DB 7 + Push 경계 3 + Activity 재생성/process lifecycle 1 = 32, failures/errors/skips 0. 최종 46초 |
| APK·instrumentation APK·server installDist | 검토 보강 후 실제 빌드·설치 성공 |
| lint | errors 0, warnings 16: 의존성 버전 안내 13 + 기존 target/backup/icon 안내 3 |
| Bounded bootstrap | 합성 기록 85개. 실제 첫 DB는 #66–#85의 20개, oldest=66/highWatermark=85/end=false |
| 과거 스크롤 중 live | #83 key/offset=293px 유지, 위치 index만 2→3. #86이 최신에 추가됨 |
| 실패·프로세스 경계 | 첫 before #66은 503. 오류/재시도 버튼·기존 기록 표시. 앱 종료 DB의 cursor/oldest=66 유지, 캐시는 live 포함 21개. 재실행 뒤 명시 버튼으로 동일 before 성공 |
| 과거 조회 중 live | retry 요청(19:52:48.933) → WS #87(49.195) → older #46–#65 commit(50.530). #84 key/offset=291px 유지 |
| 끝까지 과거 조회 | before 66→46→26→6, #1 도달 후 oldest=1/end=true/nextBefore=null. 완료 화면 확인 |
| Android↔Bob peer | Alice own-paging #88 HTTP/WS 수락 한 행 → Bob WS 수신/HTTP 응답 #89 → Android 수신. ROUND_TRIP_PASS. 콘솔 historyCount=20은 현재 API의 최신 페이지 개수 |
| 실제 배경/복귀 | ProcessLifecycleOwner socketStopped 로그 뒤 #90/#91을 서버에 추가. 배경에는 WS receive 없음. 전경 복귀 bounded bootstrap hwm=91 및 두 본문 표시 |
| 최종 서버/DB 대조 | HTTP 전체 cursor 순회 #1–#91과 Alice 캐시 91개 ID/본문/순서 일치, 중복·누락 없음. key(1,91,end=true), Alice SENT outbox 한 행 #88 |
| 새 프로세스·오프라인 Paging | 서버/proxy 종료·reverse 제거·포트 닫힘 확인. PID 9257→10968, 캐시에서 최신20개 밖 #63–#66 표시. Bob 빈 캐시/Alice 복원, 네 테이블 모든 필드 동일 |
| 증거/스크립트 검사 | verify-paging-evidence.py 6개 PASS, Python 문법·capture bash 문법·git diff 검사 PASS |

코드를 구현 이후 별도 관점으로 다시 읽어 데이터 흐름·접근 검사·중복·오류 표시를 확인했다. 별도 외부 에이전트 검토나 자료 전송은 하지 않았다. DB Paging의 위치 Int key와 서버 opaque before key를 분리하고 과거 기록+key를 한 transaction으로 묶었다. 요청 경계 바로 앞까지 연속된 응답만 허용한다. scope/본문 충돌은 rollback, 늦은 응답은 현재 key가 요청 key와 같을 때만 전진한다. Room의 미수락 outbox→accepted 행은 stable key를 유지한다. Paging DB 읽기 실패와 서버 과거 조회 실패를 별도로 표시한다.

전경 소켓은 Application의 ProcessLifecycleOwner에 속하고 ViewModel은 facade다. Push/HTTP/WS/page는 공통 repository/cache를 사용하며 전달 계정이 현재 UI 계정과 달라도 원래 scope에 저장한다. 실제 ActivityScenario 재생성은 generation을 유지하고, CREATED/RESUMED는 배경 취소/새 전경 generation을 확인했다. 로컬 Push tests는 중복·늦은 원래 계정 결과·SENT 역전 방지·metadata 검사·socket 미시작을 확인했다.

### 실패·중단 뒤 최종 검증

- 첫 serial 지정 기기 검사 32개는 31 통과/1 실패였다. 단독 cold PagingSource의 invalidation callback 대기가 timeout이었다. 실제 Pager collector의 generation/81번 live row를 검사하도록 고쳐 7개 재검사 통과 후 최종 32개 전체를 재실행했다. 최초 실패 로그를 보존했다.
- 그 이전 첫 기기 명령에는 serial 제한을 빠뜨려 영상용 emulator-5558에도 dev.chatlab/dev.chatlab.test APK가 설치됐다. 해당 명령을 중단하고 이후 ANDROID_SERIAL와 android.injected.device.serial을 모두 5554로 지정했다. 영상 앱 삭제·종료·데이터 초기화를 수행하지 않았다. 영상 담당의 전용 AVD 종료·5558/8090 닫힘 확인 후 순차 기기 검증을 재개했다.
- 동시 AVD 사용 중 System UI 오류/ADB timeout과 Mac 메모리 약 31GB/압축 약 15GB가 관찰됐다. 대기 중 채팅 AVD도 종료됐고 로그에 ColorBuffer 오류·크래시 기록이 있었다. 정확한 크래시 원인으로 메모리 부족을 단정하지 않았다. 읽기 전용 Pixel_8a를 headless로 다시 실행해 최종 32개와 데모를 통과했다. 다른 프로세스를 종료하지 않았다.
- 과거 응답 직후 첫 XML은 아직 ‘조회 중’이라 완료 화면 assertion이 실패했다. OLDER_CACHED end=true 뒤 안정된 history-end-final XML/PNG로 다시 확인했다. 오프라인 첫 dump의 null root는 제한된 재시도로 회복했다. 오류 표시 때문에 목록 bounds가 바뀌어 초기 고정 좌표 swipe가 움직이지 않아, 관찰한 XML bounds 안의 swipe로 최종 #63–#66을 확인했다. 초기 캡처를 완료 근거로 사용하지 않았다.

### 재현·근거·남은 경계

[15분 실습](PAGING_EXERCISE.md)은 빈 서버에 85개 fixture를 먼저 만들고 앱을 시작해 before/실시간/실패 위치를 예측한다. 과거를 조회하며 같은 key/offset이 유지되는지 관찰하고, 다른 서버 실행으로 전환한 뒤 늦은 과거 응답을 병합하는 테스트를 사용자가 추가한다. 일반 서버에는 실패 제어 경로가 없다. page-failure 프록시는 src/test의 별도 loopback 실행이며 debug APK에서만 선택한다.

로컬 `evidence/paging/`의 server-junit(13), jvm-junit(6), final-room-results(32), review-build.log, room-final-verification.log, lint-results-debug.xml, proxy/app/peer 로그, UI XML/PNG, history JSON, initial20/after-failed/online-final/offline-final DB+WAL이 근거다. 최초 실패/중단 로그와 7개 재검사도 별도로 보존했다. 공개 Git에는 DB 내용·로그·화면·SDK 설정을 넣지 않고 schema v1/v2/v3만 관리한다.

```bash
python3 scripts/verify-paging-evidence.py
# 자신의 앱을 멈춘 뒤 현재 DB/WAL만 캡처:
CHAT_EVIDENCE_GROUP=paging bash scripts/capture-outbox-db.sh <serial> <label>
```

이 작업의 서버·프록시·읽기 전용 AVD는 종료한다. 종료한 read-only AVD의 앱 데이터를 다음 실행의 영속 DB로 취급하지 않는다. 기기/서버를 새로 시작하고 로컬 fixture로 재현한다.

실제 FCM·알림 권한 거절·배경 Push delivery·OS 메모리 kill 후 FCM wake-up·실기기·두 Android 동시 채팅·release APK·디스크 손상/용량 부족 주입은 미실행이다. 배경동안 **두 개** 기록의 복귀 수신은 확인했으나 최신20개를 넘는 자동 누락 보충은 구현/검증하지 않았다. NeedsCatchUp은 내구성 있는 sync queue가 아니다. 서버는 메모리이며 테스트 헤더는 제품 인증이 아니다. [배경 계약](BACKGROUND_CONTRACT.md)에 최소 연결 입력 및 force-stop/OS kill 차이를 남겼다. 다음 한 가지는 연속 확인 지점 이후의 bounded after catch-up이다.

## 여섯 번째 단위: 연속 확인 지점·자동 after 복구·FID 준비 — 2026-10-05 UTC

착수 checkout은 `05e11dccf08774fb899d498300c9a2b62fe4fb25`이며 local/remote main이 일치했다. 기존 Mac/SDK/AVD를 관찰하고 다른 실행 기기가 없는 상태에서 채팅 전용 emulator-5554만 사용했다. 모든 instrumentation에 ANDROID_SERIAL와 android.injected.device.serial을 함께 지정했다. 공식 프로젝트 의존성으로 Messaging25.1.3, WorkManager2.11.2, Google services plugin4.5.0을 추가했다. 시스템 도구 설치·기존 저장소 수정·외부 에이전트 전송은 없다.

| 검사 | 최종 실제 결과 |
|---|---|
| 서버 unit/loopback 통합 | 17 tests, failures/errors/skips0. after 고정 target/20개, 입력·방·실행 접근, FID 단일 target serializer, 실제 프록시 두 번째 after503/같은 위치 retry/live 경계 포함 |
| Android JVM | 8 tests, failures/errors/skips0. 기존6 + FCM hint 입력/receipt ID 출력 차단2 |
| Android API34 instrumentation | 42 tests, failures/errors/skips0, 최종18초. 기존32 + catch-up DB/coordinator8 + FCM binding/기본 초기화 차단2 |
| APK·test APK·server installDist | 빌드 성공. 최종 앱 소스 JVM/APK/lint 재검사 성공 |
| lint | errors0, warnings20. 기존 target/backup/icon3, 의존성 안내15, UseKtx2. legacy onNewToken lint는 실제 FID onRegistered 계약을 설명한 좁은 suppress 적용 |
| 첫 기준점 | 서버85개, 실제 앱 최신 #66–#85만20개. Room base65/contiguous85/target85 |
| 배경/복귀120개 누락 | PROCESS_BACKGROUND socketStopped 뒤 #86–#205 추가. 복귀 tail #186–#205가 저장돼도 confirmed85/target205 |
| 중간503·DB 경계 | after85 #86–#105 commit 뒤 after105만503. 오류/재시도 UI. partial DB는60행(#66–#105 + #186–#205), base65/confirmed105/target205. max(sequence)=205를 confirmed로 취급하지 않음 |
| 새 프로세스 이어받기 | PID10382→10499. 요청 after85→105(503)→105→125→145→165. 첫 재개는 저장된105, cursor regression/자동 POST 재전송 없음 |
| HTTP 중 WS 높은 번호 | after105/through205 요청 중 WS #206(23:14:37.114) → 첫 페이지 commit 뒤 confirmed125/target206(38.346). 다음 요청은 through206. 끝에서 cached tail과 연결되어 confirmed206 |
| 최종 서버/DB 대조 | HTTP 전체 history206개 중 base65 이후 #66–#206의141개가 Alice 캐시와 ID/clientID/본문/sender/시각/실행/순서 모두 일치. UI stable key141개 unique. 자기 outbox0개인 수신 전용 fixture |
| before 독립 | partial/recovered의 nextBefore·oldest186·end=false 동일. after가 before key를 소비하지 않음. #1–#65는 자동 복구 범위 밖 |
| 오프라인 Paging | 자신의 서버/proxy 종료·reverse 제거 뒤 새 프로세스. 실제 missed097–100(#182–#185)이 화면에 표시되어 복귀 latest20개 밖의 복구 행을 Room에서 읽음. outbox/cache_sessions/cached_messages/history_keys/sync_cursors/sync_hints 여섯 테이블 모든 필드 동일 |
| 실제 알림 거절 분기 | API34 자신의 APK의 POST_NOTIFICATIONS 거절 상태에서 durable hint/target 보존과 showIfAllowed=false 검사. 원래 허용 상태면 검사 후 복원. 외부 FCM 전달 검사는 아님 |
| FCM 기본 차단 | 실제 FirebaseApp 목록 비어 있음, CHAT_FCM_ENABLED=false에서 등록 호출 거절·binding 미생성. merged manifest의 FirebaseInitProvider 제거 확인. 설정 없는 opt-in 빌드는 의도대로 실패 |
| CLI 상태 | 기존 Firebase CLI15.10.0, 로그인 계정1개, projects:list 성공/접근35개. 이메일·인증정보 미출력. 앱 생성·설정 다운로드·등록·외부 sender 실행 없음 |
| 증거 대조 | verify-catch-up-evidence.py 5PASS, Python/bash 문법·git diff 검사 |

추가 DB 검사는 높은 Push/WS가 bootstrap보다 먼저 도착해도 기준을 만들지 않는 경우, 역순81/82, 동시10개 coordinator 합류·live201·늦은 중복, 여러 페이지 중단/파일 DB 재열기, malformed gap/immutable conflict의 receipt+cursor rollback, 계정·서버 실행·늦은 원래 scope 병합, 실제 v3 schema→v4 migration, hint 내구성과 알림 거절을 확인한다. 보강한 cancellation 검사는 첫 after commit 전 취소는 confirmed20/UNKNOWN 유지, 첫 페이지 commit 후 다음 요청 중 취소는 confirmed40/SENT 유지, 재개 after40 및 Bob 분리/자기 stable key를 검사한다. 실제 foreground 세션의 gate를 둔 UI generation 취소 경쟁은 실습의 사용자 추가 과제로 남는다.

### 실패 뒤 해결과 해석

- 첫 Firebase 컴파일은 getInstance(FirebaseApp)이 public이라고 가정해 실패했다. 실제25.1.3 API를 javap로 확인하고 default FirebaseApp + public getInstance()/register()/onRegistered를 사용해 수정했다. 현재 SDK는 FID API를 지원하며 token fallback을 추가하지 않았다.
- 새 DB 검사 첫 컴파일은 HttpResponseData import/혼합 SQLite binding array type이 없어 실패했다. 명시 import/arrayOf<Any>로 수정한 뒤 빌드·기기 검사 통과했다.
- 첫41개는40통과/1실패였다. before 키 전체 불변을 기대한 검사가 live201에 의한 정상 highWatermark 확장을 실패로 계산했다. nextBefore/oldest/end는 불변이고 highWatermark는201인 것을 분리해 수정했다. 이후41개 전체 통과, cancellation 보강 뒤 최종42개 전체 통과했다.
- 증거 스크립트의 첫 실행은 WS206 직후 confirmed105/target206 로그가 있다고 가정해 실패했다. progress 로그는 HTTP commit 뒤 남아 confirmed125/target206이며, WS206이 먼저였음을 실제 시간순으로 검증하도록 고쳤다. 처음 오프라인3회 swipe는 latest20 안에 머물렀다. 관찰한 목록 bounds로7회 이동하여 최종 #182–#185를 확인했다. 처음 캡처/실패 스크립트를 완료 근거로 사용하지 않았다.
- `-PchatFcmEnabled=true`를 설정 없이 실행한 실패는 보호 장치의 기대 결과다. 실제 FCM SDK 등록/외부 전달 실패라고 계산하지 않는다.

### 검토·근거·남은 범위

구현 이후 데이터 흐름·scope/권한·중복·오류를 따로 검토했다. 서버 접근 검사 뒤 cursor를 해석하고 after target은 요청 범위에 고정한다. Room은 sequence 바로 다음 실제 행만으로 contiguous를 전진시킨다. before와 after 키를 분리하고 동일 transaction에서 본문/자기 outbox receipt/연속 cursor를 갱신한다. 늦은 유효한 원래 scope의 응답은 보존되며 새로운 UI 계정이나 서버 실행으로 옮기지 않는다. coroutine 취소는 기존 commit을 되돌리지 않는다. 오류는 받은 기록과 별도로 표시한다.

FCM 준비는 SDK onRegistered receipt만 사용하며 FIS.getId/Task 성공을 등록 완료로 오인하지 않는다. binding은 UI Chip과 독립이며 FID는 private preferences에만 둔다. 로그/화면/Git에 ID/인증키를 출력하지 않는다. Service는 짧은 hint 저장 후 HTTP-only worker를 예약한다. 서버의 FID serializer는 순수 body 생성 코드이며 자격증명·네트워크 sender·서버 registration 저장 API는 없다.

로컬 `evidence/catch-up/`에는 build-final/review-build/server-final/room-final 로그, server-junit17/JVM-junit8/final-room-results42, lint XML, SDK public-api 검사, CLI 읽기 전용 요약, initial20/partial/recovered/offline DB+WAL, UI XML/PNG, proxy/Android 로그, history206 JSON, PID, evidence-verification.log가 있다. 최초 컴파일/검사 실패와41개 통과도 별도 보존한다. 공개 Git에는 schema v1–v4·소스·검사·실습만 관리하고 DB/화면/로그/설정은 제외한다.

```bash
python3 scripts/verify-catch-up-evidence.py
CHAT_EVIDENCE_GROUP=catch-up bash scripts/capture-outbox-db.sh <serial> <label>
```

이 작업의 loopback 서버·프록시·read-only AVD만 종료한다. 다음 실행은 [실습](CATCH_UP_EXERCISE.md)의 새 합성 fixture로 재현한다. 종료한 read-only AVD의 앱 데이터가 다음 실행에 남는다고 주장하지 않는다.

실제 FCM 프로젝트 설정/앱 등록/SDK onRegistered/배경 data delivery/알림 허용 UX/외부 sender/OS kill 후 wake-up은 미실행이다. 실기기·두 Android 채팅·release APK·서버 영속 DB·자동 reconnect 백오프도 미실행이다. 프로젝트 생성/선택·dev.chatlab 등록·지정 기기/신원·발송 경로 승인을 기다리며 준비된 FID 경로로 실제 배경 수신을 다음 한 가지로 검증한다. 메모리 서버와 로컬 테스트 헤더의 한계는 그대로다.
