# 전경 소켓과 배경 Push의 경계

현재 구현은 전경/배경 진입 경로의 분리와 로컬 adapter 검사까지입니다. 실제 Firebase Messaging SDK, token, FirebaseMessagingService, 알림 채널/권한 요청, 외부 sender는 없습니다. 실제 배경 수신 완료로 해석하지 않습니다.

## 현재 소유권

```text
Application / ProcessLifecycleOwner
  ON_START → ForegroundChatSession → WebSocket bounded bootstrap + live
  ON_STOP  → 해당 소켓 job 취소, generation 변경

LocalPushAdapter.receive(boundAccount, PushEnvelope)
  → ChatRepository.receive → 같은 Room transaction
  또는 NeedsCatchUp(계정, 방, 서버 실행, throughSequence)

ChatViewModel → 프로세스 세션에 UI 명령만 위임
화면 → Room PagingSource/Pager의 PagingData만 표시
```

앱에 전경 Activity가 있는 동안 소켓을 유지합니다. Activity 회전/재생성과 화면 이동은 앱 배경으로 취급하지 않습니다. ProcessLifecycleOwner는 configuration change의 일시적인 Activity 정지를 배경으로 오판하지 않도록 지연해서 ON_STOP을 전달합니다. [공식 lifecycle 설명](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner)을 기준으로 합니다.

배경 전환은 다음 lifecycle callback까지 약간 지연될 수 있습니다. 경계에서 이미 처리 중이던 WS 저장이 끝나거나 Push가 같은 메시지를 다시 전달해도 원래 계정·방에 저장하고 같은 실행/서버 ID로 중복을 합칩니다. 새 callback은 종료된 generation이면 무시합니다. 취소가 이미 commit한 Room 기록을 rollback하지는 않습니다. 계정 전환 뒤 늦은 HTTP 수락/이미 시작한 병합도 원래 계정에 남고 새 계정 화면으로 새지 않습니다.

`boundAccount`는 실제 로그인/토큰 등록 경계가 제공해야 합니다. payload의 recipientId만 믿고 계정을 선택하면 안 됩니다. 로컬 adapter는 Alice/Bob/demo 테스트 신원만 허용하며 recipient/room/serverInstance/sequence와 Message metadata를 맞춥니다. 이 검사는 제품 인증을 대신하지 않습니다.

## 복귀 catch-up은 과거 탐색과 다르다

- `before`: 지금 본 가장 오래된 경계 **이전**을 읽는 과거 탐색입니다.
- 미래 `after`: 마지막으로 연속 확인한 `(serverInstanceId, sequence)` **이후**를 bounded page로 받아 누락을 보충해야 합니다.

전경 복귀의 WS 최신 20개만으로 배경 동안 100개가 왔을 때 전체 수신을 보장하지 못합니다. 현재는 최신 tail을 저장하고 과거 cursor를 그 경계에서 시작하게 합니다. 사용자가 끝까지 과거 조회하면 현재 서버 실행의 남은 과거를 가져올 수 있지만 자동 catch-up이라고 부르지 않습니다. `catchUpRequired` 상태와 `NeedsCatchUp` 결과는 이 후속 작업이 필요함을 나타내며, 실제 after API/worker/내구성 있는 sync queue는 아직 없습니다.

다음 연결 단위에서는 계정·방별 마지막 연속 확인 지점을 저장하고, 전경 복귀와 Push hint가 같은 sync coordinator에 합쳐져야 합니다. HTTP page+WS 중복은 현재 repository/Room 경로를 재사용합니다. after 응답이 끝났더라도 sync 시작 뒤의 live 경계를 함께 대조해야 합니다. serverInstance가 바뀌면 이전 메모리 서버의 누락은 복원할 수 없으므로 캐시는 보존하고 경계를 명시해야 합니다.

## 실제 FCM을 연결할 때 지킬 계약

FCM은 중복·지연·순서 변경·유실 가능성을 전제로 수신해야 합니다. Push를 완전한 history나 정확히 한 번 전달로 취급하지 않습니다. body가 있으면 동일 Message 키로 저장하고, ID/hint만 있거나 처리 시간이 부족하면 동기화를 예약합니다. notification과 data 메시지의 배경 진입 경로가 다르고 callback 처리 시간이 제한되므로 payload 정책과 worker 처리를 함께 결정해야 합니다. [FCM Android 수신 설명](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)을 참고합니다.

알림 표시와 데이터 정합성을 분리합니다. Android 알림 권한을 거절해도 채팅 화면과 복귀 동기화가 동작해야 하며, 알림을 못 띄웠다고 서버에 메시지가 없다고 판단하지 않습니다. 실제 권한 거절/FCM delivery는 아직 검증하지 않았습니다. [FCM Android 설정·권한](https://firebase.google.com/docs/cloud-messaging/android/get-started)을 참고합니다.

OS가 메모리 때문에 프로세스를 종료한 것과 사용자가 Settings/adb로 force-stop한 것은 다릅니다. 프로세스 종료 후에는 수신 진입점이 새 프로세스를 시작할 수 있지만 이를 모든 기기/배터리 정책에서 보장하지 않습니다. force-stop은 앱이 중지된 상태이므로 사용자가 다시 열기 전 Push로 깨우는 동작에 의존하지 않습니다. Firebase의 [Android force-quit 설명](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages)은 수동 재실행이 필요함을 명시합니다. 이번 outbox force-stop 검사는 DB 복구 검사이며 FCM 수신 검사가 아닙니다.

## 이후 연결에 필요한 최소 입력

1. 사용자가 사용할 기존 Firebase 프로젝트와 dev.chatlab Android 앱 설정. 현재 저장소에는 google-services.json/FCM 설정이 없습니다.
2. 그 프로젝트에 메시지를 보낼 승인된 서버 인증 경로. 인증정보를 APK/Git에 넣지 않습니다. 새 서비스 계정이나 지속 자격 증명은 현재 생성하지 않았습니다.
3. 실제 로그인 계정↔기기 등록 신원의 binding/갱신/로그아웃 해제 계약. 테스트 X-Test-User는 제품용 binding이 아닙니다.
4. Google Play services가 있는 검증 기기와 외부 FCM 전달이 가능한 네트워크, notification/data payload 정책과 Android 알림 권한 UX.

이 입력이 결정되기 전에는 외부 Firebase 연결과 실제 배경 알림 수신을 완료했다고 보고하지 않습니다.
