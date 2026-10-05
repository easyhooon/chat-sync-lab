# 현재 채팅 실행 범위

독립 로컬 학습 프로젝트이며 서버 하나와 Android app 하나로 구성한다. Kotlin/Ktor 서버 + Compose/Ktor Client(OkHttp 엔진), Room outbox·수신 캐시·Paging을 사용한다. 서버/클라이언트는 JSON 계약으로 분리되어 있어 서버 프레임워크를 바꿔도 메시지 정합성 문제는 같다.

- 테스트 신원 `alice`, `bob`; 대화방 `demo`; 텍스트 1–1000자. 실제 로그인·읽음·영상은 없다.
- 화면: 테스트 신원 선택, 연결/기기 저장 상태, Paging 메시지 목록, 입력, 전송, UNKNOWN 수동 retry, 과거 조회/실패 retry/최신으로 이동.
- 전송 상태: SENDING → SENT(서버 메모리 수락), FAILED(거절), UNKNOWN(응답 유실 가능). SENT는 영속 서버 저장·상대 수신·읽음을 뜻하지 않는다.
- 서버는 `127.0.0.1:8080`만 listen한다. Android는 관찰한 serial의 adb reverse로 loopback에 연결한다. 외부 공개·방화벽 변경·유료 서비스·Firebase는 없다.
- `X-Test-User`는 누구나 흉내 낼 수 있는 로컬 개발 신원이다. HTTP와 WS 업그레이드 전에 신원 및 방 접근을 검사한다. 제품 인증을 대신하지 않는다.
- 전경 WS는 Application/ProcessLifecycleOwner 소유다. Activity 회전은 연결을 끊지 않으며 배경에서는 WS job을 취소한다. 수동 reconnect는 있지만 백오프 자동 reconnect/retry는 없다.
- UI 본문은 계정·방별 Room PagingSource만 읽는다. outbox 송신 상태만 transient 오류/retry 정책용 StateFlow에도 관찰한다. 네트워크 callback은 UI 목록을 직접 바꾸지 않는다.

## 현재 API 계약

신원 헤더: `X-Test-User: alice` 또는 `bob`. 모든 방 경로는 신원 미지정/알 수 없음 `401`, 접근 불가 방 `403`이다.

`GET /health` → `{"status":"ok"}`

`GET /rooms/demo/messages?limit=20&before=<opaque cursor>` → History. limit 기본 20, 허용 1–50. before 없으면 최신 페이지, 있으면 cursor sequence보다 작은 과거 페이지다. 각 페이지 안은 sequence 오름차순이다. cursor는 방·서버 실행·배타적 sequence를 포함한다. 잘못된 cursor/다른 방 `400`, 이전 서버 실행 `409 CURSOR_EXPIRED`다.

```json
{"messages":[],"serverInstanceId":"server process UUID","roomId":"demo","nextBefore":null,"endOfHistory":true,"highWatermark":0}
```

빈 서버 또는 #1까지 읽으면 nextBefore=null/endOfHistory=true다. highWatermark는 응답 생성 시점의 현재 방 마지막 sequence이며 after catch-up 완료를 의미하지 않는다.

`POST /rooms/demo/messages` body:

```json
{"clientMessageId":"UUID","text":"hello"}
```

→ `201 Message`; 같은 `(roomId, senderId, clientMessageId)`와 같은 text 반복은 `200`과 기존 Message. 같은 키/다른 text `409`; 잘못된 UUID/빈 텍스트/1000자 초과 `400`이다. sender는 요청 body가 아니라 검사한 헤더 신원에서 정한다.

```json
{"id":"server UUID","clientMessageId":"client UUID","roomId":"demo","senderId":"alice","text":"hello","sequence":1,"createdAt":"ISO-8601 UTC","serverInstanceId":"server process UUID"}
```

`WS /rooms/demo/events`:

```json
{"type":"snapshot","page":{"messages":[],"serverInstanceId":"server process UUID","roomId":"demo","nextBefore":null,"endOfHistory":true,"highWatermark":0}}
{"type":"message","message":{}}
```

첫 snapshot은 최신 **20개** 페이지다. page 생성과 subscribe 등록을 append와 같은 lock에서 처리한다. 그 시점 이후 append는 live 이벤트로 이어진다. Android가 GET→WS로 시작하지 않아 그 사이 누락 틈이 없다. 과거 기록은 before로 별도 조회한다. 느린 구독자의 64개 채널이 넘치면 서버는 해당 연결을 닫는다. HTTP 응답과 WS echo 순서는 보장하지 않는다.

에러 body: `{"code":"...","message":"..."}`. 서버는 메모리이므로 재시작 뒤 idempotency 인덱스와 sequence도 초기화된다. 이전 서버 실행의 누락을 새 서버에서 복원할 수 없다.

## 저장·중복·오류 계약

- Room에 `(계정, 방, clientMessageId)`의 ID·본문·상태·생성 시각을 저장 완료한 뒤만 POST한다. 저장 실패는 입력을 유지한다. 로컬 commit과 서버 append는 하나의 transaction이 아니다.
- 새 프로세스의 남은 SENDING은 UNKNOWN으로 한 번만 복구한다. reconnect/계정 전환은 살아 있는 HTTP 요청을 재복구하지 않는다. UNKNOWN 버튼은 DB의 조건부 갱신으로 한 번만 claim하며 원래 ID·본문을 전송한다.
- 모든 수신 경로는 같은 캐시 transaction으로 기록하며 자기 outbox 수락도 함께 갱신한다. SENT를 늦은 실패가 되돌리지 않는다. 계정 변경 뒤 결과도 원래 계정·방에 저장한다.
- 캐시 키는 `(관찰 계정, 방, 서버 실행, 서버 ID)`다. 실행별 sender/client ID와 sequence에도 unique 제약이 있다. 같은 키의 본문/순서 충돌은 덮어쓰지 않고 실패한다.
- snapshot/과거 페이지는 추가 병합이며 캐시를 삭제하지 않는다. 실행 그룹은 처음 관찰한 ordinal, 그룹 내부는 sequence, 미수락 outbox는 로컬 생성 순서다. 전역 시간순을 주장하지 않는다.
- 과거 응답은 요청 경계 바로 앞까지 연속이어야 한다. 기록+HistoryKey는 한 transaction이다. 실패 시 cursor를 유지하고 수동 retry한다. 늦은 응답은 이미 진행한 cursor를 되돌리지 않는다.
- Room v1→v2→v3 migration은 기존 outbox/캐시를 보존한다. 빈 서버·오프라인에서도 기기가 다운로드한 기록을 읽는다. Paging DB 읽기 오류와 서버 과거 조회 오류를 구분해 표시한다.

## 테스트 전용 실패 주입

일반 서버에는 실패 제어 API가 없다. `server/src/test`의 별도 loopback 프록시를 명시 실행하고 debug APK 모드를 선택한다. 기본/릴리스는 8080 직접 연결이다.

| scenario | 포트 | 주입 |
|---|---|---|
| both | 8081 | 첫 Alice POST의 HTTP 수락을 timeout보다 늦추고 같은 WS 수락을 숨김 |
| http-only | 8082 | HTTP 수락만 늦춤, WS는 전달 |
| page-failure | 8081 | 첫 before GET에 503, 나중 과거 조회는 잠깐 지연 후 정상 전달. POST/WS 정상 |

같은 포트의 both/page-failure는 동시에 실행하지 않는다. 반복 실험은 본인이 시작한 프록시만 다시 실행한다.

## 이번 경계와 다음 한 가지

[페이징 계약](PAGING_CONTRACT.md), [배경 계약](BACKGROUND_CONTRACT.md)을 따른다. Push는 로컬 adapter 검사만 있으며 실제 FCM SDK·token·service·알림 권한·외부 전달은 없다. before 과거 탐색은 after 누락 보충과 다르다. 최신 20개 bootstrap으로 배경 동안의 모든 메시지를 받았다고 주장하지 않는다. 다음 한 가지는 계정·방·서버 실행별 연속 확인 지점 이후의 bounded after catch-up이다.
