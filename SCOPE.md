# 첫 실행 단위

기존 앱·저장소와 독립된 로컬 학습 프로젝트. 서버와 Android 클라이언트 사이에서 메시지가 실제로 오가는 경로를 확인한다.

스택: Kotlin/Ktor 서버 + Compose/Ktor Client(OkHttp 엔진). 서버/클라이언트는 HTTP/WebSocket JSON 계약으로 분리된 독립 선택이다. Spring Boot도 요구사항을 충족하지만 지금은 Spring 학습보다 메시지 전송 경로를 직접 확인하는 것이 목표라 Ktor로 시작한다. 서버 하나·Android app 하나만 구성한다.

- 로컬 테스트 신원 `alice`, `bob`; 방 `demo`; 텍스트 1–1000자.
- 화면: 테스트 신원 선택, 연결 중/연결됨/연결 끊김, 메시지 목록, 입력, 전송.
- 전송 상태: `SENDING` → `SENT`(서버 수락) 또는 `FAILED`(거절)/`UNKNOWN`(응답 유실 가능). `SENT`는 상대의 읽음·수신 확인이 아니다.
- 서버: 메모리 기록, 프로세스 재시작 시 초기화. REST 전송·기록 조회 + WebSocket 실시간 이벤트.
- 첫 접속: 서버가 구독 등록과 기록 snapshot을 동일 잠금에서 처리한다. 이후 이벤트를 서버 순서대로 보낸다.
- 화면 진입·복귀 시 연결한다. 연결 장애 후 백오프 자동 재시도는 없고 버튼으로 다시 연결한다. 영속 outbox/DB, 읽음/배달 상태, 페이징, 실제 인증은 다음 단계.
- 서버는 `127.0.0.1:8080`만 listen. Android는 `adb reverse tcp:8080 tcp:8080` 후 `127.0.0.1:8080`을 사용한다. 외부 공개·방화벽 변경 없음.
- 로컬 개발용 `X-Test-User`는 누구나 흉내 낼 수 있는 테스트 신원이다. 실제 인증/보안이 아니다. HTTP/WS 모두 신원과 방 멤버십을 검사한다.

## API 계약 v1

신원 헤더: `X-Test-User: alice` 또는 `bob`. 모든 방 경로는 신원 미지정/알 수 없음 `401`, 접근 불가 방 `403`.

`GET /health` → `{"status":"ok"}`

`GET /rooms/demo/messages` → `{"messages":[Message...]}` (sequence 오름차순, 현재 전체 기록).

`POST /rooms/demo/messages` body:
```json
{"clientMessageId":"UUID","text":"hello"}
```
→ `201 Message`; 같은 `(roomId, senderId, clientMessageId)`와 같은 text 재전송은 `200`과 기존 Message. 다른 text로 같은 키를 재사용하면 `409`. 잘못된 UUID/빈 텍스트/1000자 초과 `400`.

```json
{"id":"server UUID","clientMessageId":"client UUID","roomId":"demo","senderId":"alice","text":"hello","sequence":1,"createdAt":"ISO-8601 UTC"}
```

`WS /rooms/demo/events`:
```json
{"type":"snapshot","messages":[Message...]}
{"type":"message","message":{}}
```
첫 프레임은 snapshot. REST 응답과 WS echo의 순서는 보장하지 않는다. Android는 `id`로 병합하고 자기 메시지는 `clientMessageId`로 pending과 맞춘다. `sequence`는 이 서버 프로세스/방 안에서만 증가한다.

에러 body: `{"code":"...","message":"..."}`. snapshot/실시간/REST 응답의 중복은 하나의 행으로 합쳐야 한다. HTTP 결과를 모르면 새 ID로 자동 재전송하지 않는다.

## 검증 목표

서버: 양방향 실시간 왕복, 기록, HTTP/WS 접근 차단, 잘못된 입력, 동일 키 재전송과 충돌, 동시 전송 순서, snapshot 구독 경계.
Android: debug APK 빌드와 상태 병합 테스트; 기기 상태 확인 후 한 Android 세션 + Bob 테스트 클라이언트 왕복·화면/로그 증거.

공식 참고: [Ktor WebSockets](https://ktor.io/docs/server-websockets.html), [AGP 9.0 / built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes).
