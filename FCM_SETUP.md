# FID 수신 연동 준비와 필요한 입력

현재 package ID는 dev.chatlab이고 Firebase 설정이 없다. 다른 개인/회사 앱의 설정을 찾거나 재사용하지 않았다. Messaging 25.1.3 프로젝트 의존성과 실제 onRegistered service를 컴파일하며, Firebase 자동 provider/auto-init/Analytics를 막고 기본 CHAT_FCM_ENABLED=false로 둔다. 현재 등록·FID 생성·외부 발송은 실행하지 않는다.

## 확인한 최신 계약 — 2026-10-05 UTC

- Android [시작 안내](https://firebase.google.com/docs/cloud-messaging/android/get-started), [등록 관리](https://firebase.google.com/docs/cloud-messaging/manage-tokens): installation_id_enabled flag + register()와 SDK onRegistered(installationId) 성공 callback을 사용한다. FirebaseInstallations.getId()만 얻은 상태는 FCM 등록 완료가 아니다. register Task 성공만으로 앱의 receipt를 먼저 기록하지 않는다.
- Android [릴리스](https://firebase.google.com/support/release-notes/android): FID API는 Messaging 25.1.0부터. 현재 25.1.3을 사용하고 실제 공식 AAR의 register/onRegistered public API를 javap로 확인했다. 앱 binding은 SDK callback에서만 FID·프로젝트·계정·등록시각을 앱 private preferences에 기록한다. ID를 로그/화면에 출력하지 않는다.
- [REST Message](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages)의 명시적 fid target을 사용한다. token/fid/topic/condition은 함께 지정하지 않는다. [Admin Java 릴리스](https://firebase.google.com/support/release-notes/admin/java)의 setFid는 9.10.0부터, 최신 9.11.0도 지원한다. 현재 서버는 HTTP v1 body serializer만 준비했고 Admin credential/SDK sender는 초기화하지 않았다. 선택한 Android SDK와 REST 계약이 FID를 지원하므로 legacy token fallback은 추가하지 않는다.

## 사용자에게 필요한 최소 입력

1. 사용자가 사용할 기존 Firebase 프로젝트 ID와 dev.chatlab 등록 여부. 없다면 Android 앱 등록도 별도 대상/승인 확인 후 진행한다.
2. 합의한 지정 테스트 기기와 테스트 신원(Alice/Bob). Google Play services가 있는 기기가 필요하다. 테스트 신원 Chip 변경은 FCM binding 변경이 아니다.
3. 해당 프로젝트의 승인된 발송 경로. 사용자 본인의 기존 sender로 발송하는지, 다른 허용 경로가 있는지 결정한다. 새 프로젝트·서비스계정·API key·OAuth·지속 접근을 생성하지 않는다.
4. 프로젝트 선택 후 dev.chatlab용 설정 파일을 로컬 app/google-services.json에 직접 제공한다. 해당 파일은 Git ignore다. 채팅에 인증정보·token·FID·서비스계정 JSON을 붙여넣지 않는다.

설정·권한·지정 대상이 결정된 뒤에만 사용자가 승인한 로컬 빌드에서 -PchatFcmEnabled=true를 사용한다. 이 빌드는 로컬 google-services.json이 없으면 실패한다. 기본 빌드는 설정 없이 동작한다. 실제 등록 시작은 debug 실행의 명시적 fcm_test_account extra로 별도 opt-in하며, 현재 실행하지 않았다. 서버 upload/send 연결은 별도 승인 단계다.

## 수신·알림·동기화 경로

FCM data는 kind=catch_up, recipientId, roomId, serverInstanceId, throughSequence의 짧은 hint다. service는 확인된 등록의 boundAccount를 payload와 독립적으로 읽어 검증하고 Room에 durable hint만 짧게 저장한다. 이후 WorkManager의 계정·방별 unique chain이 공통 coordinator로 HTTP bootstrap/after를 수행한다. 배경 worker는 소켓을 열지 않는다. foreground WS/HTTP도 같은 transaction과 coordinator를 사용한다.

notification+data는 배경에서 callback 진입이 다르므로 이 테스트 계약은 data-only를 사용한다. 자체 알림은 권한이 허용된 경우에만 표시하며, 거절해도 hint 저장/복귀 sync를 막지 않는다. 알림 클릭은 계정을 임의 전환하지 않고 현재 UI를 연다. WorkManager는 hint가 마지막 실행 경계에 도착해도 후속 실행이 있도록 APPEND_OR_REPLACE를 사용한다. 중복 payload는 Room에서 합치지만 여러 background 작업을 예약할 수 있다.

force-stop은 사용자 재실행 전 수신/worker wake-up에 의존하지 않는다. OS process kill은 다른 경계이며 배터리/제조사 정책으로 전달을 보장하지 않는다. 이 설정 대기 중인 작업에서는 실제 FCM 수신·알림·OS kill 후 wake-up을 통과로 표시하지 않는다. 서버는 계속 메모리이고 제품 인증/내구성 있는 서버 registration 저장은 다음 범위다.

## CLI로 준비할 수 있는 범위

개인 Mac의 Firebase CLI 15.10.0과 기존 로그인 계정 1개를 읽기 전용으로 확인했다. `login:list`와 `projects:list`는 성공했고 접근 가능한 프로젝트가 35개다. 계정 이메일·인증정보·다른 앱 설정은 자료에 출력하지 않는다. [공식 CLI 안내](https://firebase.google.com/docs/cli)와 설치된 명령 도움말에서 아래 문법을 확인했다.

프로젝트를 사용자가 지정한 뒤 먼저 앱을 조회한다. `$CHAT_FIREBASE_PROJECT`와 `$CHAT_FIREBASE_APP_ID`는 사용자가 선택한 실제 값으로 설정하며 현재 실행하지 않았다.

```bash
firebase apps:list ANDROID --project "$CHAT_FIREBASE_PROJECT" --non-interactive
# dev.chatlab이 없다면 Android 앱 등록 승인 후에만:
firebase apps:create ANDROID "Chat Sync Lab" --package-name dev.chatlab --project "$CHAT_FIREBASE_PROJECT" --non-interactive
# 조회/등록 결과의 정확한 appId로 로컬 ignored 파일에 저장:
firebase apps:sdkconfig ANDROID "$CHAT_FIREBASE_APP_ID" --project "$CHAT_FIREBASE_PROJECT" --out app/google-services.json --non-interactive
./gradlew -PchatFcmEnabled=true :app:assembleDebug
```

CLI 설치/새 로그인은 필요 없다. 프로젝트 선택·dev.chatlab 등록 대상·테스트 기기·발송 경로가 아직 확정되지 않아 위 명령으로 앱 생성·설정 다운로드·실제 등록을 수행하지 않았다. CLI 설정 다운로드는 인증키를 채팅에 붙여넣는 작업이 아니다. 기존 로그인 토큰이나 서비스계정 파일을 읽어 sender 자격증명으로 전용하지 않는다.
