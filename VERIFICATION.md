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

## 남아 있는 한계

- Lint 경고 12개: 이전 target SDK 1개, 의존성 최신 버전 안내 9개, backup 설정 1개, 앱 아이콘 1개. 로컬 첫 실행에는 오류가 없지만 제품화 전에 정리해야 한다. 경고를 숨기지 않았다.
- 두 에뮬레이터 동시 왕복·실기기·Android instrumentation UI 테스트는 미실행. 합의한 한 Android 세션 + 테스트 peer를 실제 사용했다.
- 자동 reconnect/backoff, 실제 네트워크 단절 중 전송/ACK 유실 주입, 영속 DB/outbox, 다중 서버, 장시간 부하·slow subscriber 스트레스는 미실행/미구현.
- `SENT`는 현재 프로세스 메모리 수락이다. 프로세스 재시작 뒤 기록·중복 키·sequence는 보존되지 않는다. 실제 인증은 없다.

## 증거

`evidence/verification.log`, 서버/Android JUnit XML, lint 보고서, `bob-client.log`, `android-round-trip.log`, `history.json`, `ui-round-trip.xml`, `round-trip.png`, `idempotency.log`를 로컬에 남긴다. 로그·스크린샷·실행 머신 설정은 공개 저장소에서 제외한다.

오류·복구 증거는 `ui-offline-final.xml`, `screen-final.png`(오류 화면), `ui-recovered.xml`, `recovered.png`이다. 처음 reverse만 제거했을 때 이미 열린 소켓은 유지됐다. 새 앱만 재시작한 뒤 오류를 확인했으며, 이는 **이미 진행 중인 POST의 응답 유실 실험은 아니다**.

로컬 증거가 있는 환경에서는 다음 명령으로 양쪽 로그·history·화면의 메시지 ID/텍스트와 중복 여부를 대조한다.

```bash
python3 scripts/verify-evidence.py
```
