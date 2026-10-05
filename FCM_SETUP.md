# FID 수신 연결과 로컬 실행

전용 Firebase 프로젝트 생성·dev.chatlab 등록·채팅용 emulator-5554/Alice 테스트를 사용자 승인 후 실행했다. 실제 SDK onRegistered receipt, 단일 FID data 전송, 새 배경 프로세스의 콜백과 HTTP-only 복구까지 확인했다. 기본 빌드는 CHAT_FCM_ENABLED=false이며 외부 등록을 자동 시작하지 않는다.

## 이번에 생성된 것과 보관 위치

- 새 전용 프로젝트를 한 번 생성하고 같은 프로젝트에 Android 앱 하나를 등록했다. 고유 projectId/appId는 ignored `local-firebase/project.json`에 기록했다. 다른 프로젝트를 재사용하지 않았다.
- 앱 등록의 기본 client 설정에는 API key 항목1개가 포함됐다. `app/google-services.json`에 권한600으로 저장하고 package/project/appId를 검사했다. 파일 내용·API key·FID·OAuth token은 출력하지 않는다. app/src 하위 설정·local-firebase·build·evidence도 Git 제외다.
- 프로젝트 초기화에 따라 기본 Firebase Admin SDK 서비스계정1개가 플랫폼에서 자동 생성됐다. 이 계정의 키 생성·다운로드·사용은 하지 않았다.
- billingEnabled=false를 실제 읽기 전용 조회로 확인했다. 유료 요금제/결제 연결은 없다. FCM API는 이미 ENABLED이고 현재 계정에 cloudmessaging.messages.create 권한이 있다. 추가 API 활성화/IAM 변경/새 OAuth 로그인·scope/ADC 설정은 수행하지 않았다.
- 기존 Firebase CLI15.10.0과 같은 principal의 기존 gcloud 활성 계정을 확인했다. 테스트 발송은 기존 계정의 단기 인증을 프로세스 메모리에서만 사용했다. 프로젝트 전체/Topic/Bob으로 보내지 않고 승인된 Alice의 실제 SDK FID 한 곳에 data-only 한 건을 보냈다. 발송/설정 스크립트는 ignored evidence/fcm에 있으며 자격증명을 포함하지 않는다.

## 최신 등록·발송 계약

[Android 시작 안내](https://firebase.google.com/docs/cloud-messaging/android/get-started), [등록 관리](https://firebase.google.com/docs/cloud-messaging/manage-tokens)에 따라 installation_id_enabled flag + register() / SDK onRegistered(installationId)를 사용한다. FIS.getId 또는 register Task 완료만으로 등록 receipt를 만들지 않는다. SDK callback만 계정·프로젝트·FID·등록시각을 private preferences에 저장한다. Messaging25.1.3의 실제 public API도 확인했다. token fallback은 없다.

[REST Message](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages)의 fid target만 지정한다. token/fid/topic/condition을 함께 보내지 않는다. [HTTP v1 인증](https://firebase.google.com/docs/cloud-messaging/send/v1-api)은 단기 OAuth 인증을 사용한다. 이번 테스트는 기존 권한만 사용했으며 서버에 지속 sender 자격증명을 설정하지 않았다. 서버의 body serializer는 순수 계약 코드다.

## 다시 실행

프로젝트와 등록된 앱은 남아 있으므로 프로젝트/app 생성 명령을 다시 실행하지 않는다. 로컬 설정이 있으면 다음으로 opt-in debug APK를 만든다.

```bash
./gradlew -PchatFcmEnabled=true :app:assembleDebug
# 먼저 기기를 관찰하고 실제 선택한 serial로 설치한다.
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 reverse tcp:8080 tcp:8080
adb -s emulator-5554 shell am start -n dev.chatlab/.MainActivity --es fcm_test_account alice
```

실제 SDK callback을 확인하기 전 등록 완료라고 판단하지 않는다. 테스트 신원 Chip 변경은 FCM binding 변경이 아니다. approved binding이 있는 opt-in 앱은 새 배경 프로세스의 Application에서도 Firebase를 초기화하며 auto-init은 계속false다. 기본 빌드는 설정 파일이 로컬에 있어도 Google services plugin/SDK 초기화를 켜지 않는다. provider는 제거되어 있다.

SDK 자동 provider/auto-init/Analytics를 막았으며 수동 register만 사용한다. 등록 Task 로그와 SDK callback 로그를 구분하고 실패 코드는 제한된 whitelist만 출력한다. FID를 화면/로그에 표시하지 않는다. read-only AVD 종료 후 기기 데이터/이번 FID를 다음 실행의 등록으로 취급하지 않는다. 새 실행은 다시 승인된 target의 SDK 등록이 필요하다.

## 실제 검증한 수신 경로

빈 서버의 baseline0을 저장하고 홈에서 PROCESS_BACKGROUND socketStopped를 확인했다. 일반 am kill 후 PID가 없어지고 stopped=false인 것을 관찰했다. server에25개를 추가한 뒤 Alice FID에 high-priority data-only hint를 한 번 전송했다. HTTP200 수락 다음에 새 배경 PID의 FCM_HINT_RECORDED와 PUSH_SYNC_COMPLETE를 관찰했다. Activity/WS 없이 cache1..25/confirmed25가 서버와 일치했다. [실제 검증 기록](VERIFICATION.md), `python3 scripts/verify-fcm-evidence.py`의3PASS가 근거다.

service는 독립 boundAccount로 recipient/room/run/sequence를 검사하고 Room durable hint를 저장한 뒤 WorkManager에 HTTP 복구를 맡긴다. notification+data는 배경 진입 경로가 다르므로 이번 계약은 data-only다. 알림 허용 여부와 동기화를 분리한다. 알림 클릭은 UI 계정을 임의 전환하지 않는다. APPEND_OR_REPLACE는 작업 종료 경계의 새 hint도 후속 실행하도록 한다.

force-stop 상태에서 수신을 검증한 것은 아니다. 실제 OS OOM·Doze·제조사 배터리 정책·실기기·알림 허용 UX·FID rotation/logout·지속 backend registration/sender는 미실행이다. 새 서비스계정/키/발송 권한/OAuth scope/지속 credential 설정이 필요하면 구체적 대상·권한·용도의 별도 승인을 먼저 받는다.

## 서버 개발자에게 공유할 때: 신규 실험과 migration의 차이

이번 프로젝트는 **신규 앱/프로젝트의 FID 경로 실험**이다. 기존 운영 token 저장소·구버전 앱·운영 sender를 이전하지 않았으므로 운영 token→FID migration 완료 근거로 사용하지 않는다. 아래 구현과 후속 확인을 구분한다.

| 항목 | 이번 구현/실제 검증 | 운영 migration에서 추가로 확인할 것 |
|---|---|---|
| Android SDK | Messaging25.1.3, installation ID flag, register/onRegistered public API 컴파일·실제 callback 확인 | 구버전 fleet/Play services·단계별 SDK rollout·기존 token 등록 동작 |
| 등록 완료 조건 | SDK onRegistered 뒤에만 private receipt 기록. FIS ID/Task 완료만으로 성공 처리하지 않음 | 서버 registration endpoint의 인증·계정/설치 binding·등록시각·중복/갱신·재등록 정책 |
| 발송 target | REST 명시적 fid 단일 대상 실제 HTTP200+callback+Room 대조. server serializer 단위 검사 | 운영 sender/Admin SDK 버전·권한·성공/오류 해석·기존 registration 저장 schema |
| legacy token 공존 | 구현/검증하지 않음. token fallback/onNewToken 경로 없음 | 구버전 token과 확인된 FID를 구분해 보관하고 대상 유형에 맞게 발송. 같은 요청에 fid/token을 함께 넣지 않음. deprecated/전환 호환 기간은 배포 시 공식 계약 재확인 |
| 식별자 갱신 | callback이 private FID/시각을 갱신하는 저장 경로, synthetic 재등록 검사 | 실제 FID rotation·서버 upsert·오래된 ID/UNREGISTERED 정리·늦은 callback/등록 경쟁 |
| 로그아웃/계정 연결 해제 | synthetic bind 변경은 이전 local receipt를 무효화. UI Chip과 binding은 독립 | 실제 auth logout hook·SDK unregister·서버 계정-설치 연결 해제·대기 worker/늦은 delivery가 이전 계정으로 새지 않는지 |
| 서버 인증 | 기존 계정의 단기 인증으로 지정 emulator Alice만 테스트. 기본 생성 서비스계정의 JSON/개인키 미생성·미사용 | 운영 환경의 승인된 자격증명/최소 권한/운영 방식 선택. 실제 private credential 입력·업로드는 소유자가 수행하고 새 설정은 별도 action-time 승인 |

[FID 등록 관리](https://firebase.google.com/docs/cloud-messaging/manage-tokens), [REST Message](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages), [Admin Java 릴리스](https://firebase.google.com/support/release-notes/admin/java)를 전달 계약의 근거로 함께 공유한다. Admin Java setFid 지원은 문서에서 확인했지만 이 프로젝트는 Admin SDK를 설치/초기화하지 않았고 순수 REST body+단기 테스트만 사용했다.
