# 전경 소켓과 배경 Push의 경계

전경 자동 after 복구와 Room durable hint를 구현했다. 실제 Firebase Messaging 25.1.3의 FID 등록/service·WorkManager 수신 경로도 컴파일하지만 기본 opt-in은 꺼져 있다. 프로젝트·테스트 기기·발송 경로가 미지정이므로 실제 FCM 전달은 검증하지 않았다. [등록 계약과 CLI 절차](FCM_SETUP.md)를 따른다.

## 현재 소유권

```text
Application / ProcessLifecycleOwner
  ON_START → ForegroundChatSession → WS 최신20 bootstrap + live
           → 공통 SyncCoordinator → HTTP after → Room transaction
  ON_STOP  → 소켓과 전경 catch-up job 취소, generation 변경

LocalPushAdapter.receive(boundAccount, PushEnvelope)
  → 공통 repository/cache: body 병합 또는 durable SyncHint

승인된 FCM opt-in의 onRegistered → private 등록 receipt
onMessageReceived → 독립 boundAccount 검증 → 짧은 Room hint 저장
                  → WorkManager → HTTP 최신 page + after (WS 없음)

ChatViewModel → 세션에 UI 명령 위임
화면 → Room PagingSource/Pager의 PagingData
```

Activity 재생성은 소켓 소유권을 바꾸지 않는다. ProcessLifecycleOwner는 configuration change의 일시적 정지를 배경으로 오판하지 않도록 지연해서 ON_STOP을 전달한다. [공식 lifecycle 설명](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner)을 따른다. 배경 경계에서 이미 commit한 저장은 유지하며 새 콜백은 종료된 generation이면 무시한다. 취소나 계정 전환이 원래 계정의 DB 기록을 새 계정으로 옮기지는 않는다.

boundAccount는 현재 UI Chip이나 payload에서 선택하지 않는다. FCM SDK 등록 완료 receipt에 연결된 로컬 테스트 신원을 독립적으로 읽는다. adapter는 Alice/Bob/demo만 허용하며 recipient/room/run/sequence와 body metadata를 검증한다. 이 binding과 X-Test-User는 제품 인증을 대신하지 않는다.

## before와 after

before는 가장 오래된 탐색 경계 이전이다. after는 이미 설정한 기준점 이후 실제로 연속 확인한 지점 다음이다. [복구 계약](CATCH_UP_CONTRACT.md)의 base/contiguous/target은 계정·방·서버 실행별로 Room에 저장한다. 높은 WS/Push 번호는 target만 올린다. 최신 bootstrap을 재수신해도 기존 연속 지점을 새 최신 번호로 바꾸지 않는다.

각 after 요청은 최대20개이며 해당 요청의 through target 이하만 반환한다. 페이지와 cursor는 같은 transaction이고 빈 구간·본문 충돌·scope 오류는 rollback한다. foreground/background coordinator는 계정·방별 Mutex를 공유한다. WS 저장은 HTTP를 기다리지 않으며, 유효한 늦은 응답·중복은 공통 캐시에서 합쳐진다. 복구 실패는 보존된 기록과 별도 오류/수동 reconnect를 표시한다. 새 live가 오면 다시 복구를 시도할 수 있다. 네트워크 장애의 자동 reconnect 백오프는 없다.

첫 bootstrap 이전의 history는 before로 직접 읽는다. 서버 실행이 바뀌면 새 기준을 설정하고 이전 캐시를 보존한다. 메모리 서버에서 사라진 과거 실행의 누락은 새 서버로 복구할 수 없다. 클라이언트 durable hint는 서버 영속성 보장이 아니다.

## FCM 준비와 실제 전달의 구분

새 API는 installation ID 등록 모드의 register() / onRegistered callback과 REST fid target이다. FIS.getId만 얻거나 register Task가 끝났다는 사실을 FCM 등록 receipt로 취급하지 않는다. SDK callback에서만 private receipt를 기록한다. [최신 설정](https://firebase.google.com/docs/cloud-messaging/android/get-started)과 [REST 계약](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages)을 확인했다.

테스트 payload는 data-only catch_up hint다. service의 제한된 처리 시간에는 Room 저장과 worker 예약만 한다. worker는 HTTP를 사용하며 소켓을 열지 않는다. unique APPEND_OR_REPLACE chain은 작업 종료 경계에 들어온 새 hint도 후속 실행하도록 한다. 같은 힌트가 여러 작업을 예약할 수 있지만 Room에서 target·본문은 중복 병합한다. 최대4회 시도 후 실패하며 이후 전경 복귀/수동 reconnect로 계속할 수 있다.

알림 허용 여부는 동기화와 분리한다. 실제 API34 기기 검사에서 자신의 앱 알림 권한이 거절된 상태로 hint 저장/target 유지와 알림 미표시를 확인했다. 알림 허용 UX·실제 FCM 배경 전달·WorkManager 외부 수신 end-to-end는 미실행이다. 알림 클릭은 UI 계정을 임의 전환하지 않는다.

OS process kill과 사용자 force-stop은 다르다. force-stop 뒤 사용자가 앱을 다시 열기 전 Push wake-up에 의존하지 않는다. 이번 force-stop은 로컬 cursor 복구 검증이다. OS kill 후 실제 전달·배터리/제조사 정책은 검증하지 않았다. [Firebase 수신 설명](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)을 참고한다.

프로젝트 선택·dev.chatlab 등록 대상·지정 기기·승인된 sender가 확정되기 전 외부 Firebase 등록/전송을 실행하지 않는다. 기존 CLI 로그인은 읽기 전용 목록 확인에만 사용했다. 다른 앱의 설정이나 자격증명을 재사용하지 않는다.
