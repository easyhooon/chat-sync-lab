# Chat Sync Lab

Kotlin 개발자를 위한 작은 실시간 채팅 학습 프로젝트. **Ktor 서버 + Android Compose/Ktor Client(OkHttp 엔진)**로 Alice/Bob 두 테스트 신원이 한 방에서 텍스트를 주고받는다.

첫 목표는 실제 네트워크 경로와 메시지 정합성을 관찰하는 것이다. 서버 하나와 Android app 하나로 구성한다. Android는 **Room outbox + 수신 캐시의 PagingSource**를 화면의 단일 읽기 경로로 쓰며, 서버는 계속 메모리다. 실제 인증·자동 재전송/재연결·읽음 기능은 아직 없다. 서버는 `127.0.0.1:8080`에만 바인딩한다.

- [범위·화면 상태·JSON 계약](SCOPE.md)
- [Android 관점으로 읽는 데이터 경로와 실패 시나리오](STUDY_GUIDE.md)
- [실제 검증 결과와 한계](VERIFICATION.md)
- [outbox 예상·재현·테스트 실습](OUTBOX_EXERCISE.md)
- [수신 캐시와 늦은 snapshot을 이해하는 15분 실습](CACHE_EXERCISE.md)
- [현재 과거 cursor·Room Paging 계약](PAGING_CONTRACT.md)
- [과거 조회 실패·실시간 수신·스크롤 실습](PAGING_EXERCISE.md)
- [전경 소켓·배경 Push의 구현 경계](BACKGROUND_CONTRACT.md)
- [연속 확인 지점과 bounded after 계약](CATCH_UP_CONTRACT.md)
- [누락120개·중단·이어받기 실습](CATCH_UP_EXERCISE.md)
- [최신 FID 등록과 CLI 연결 준비](FCM_SETUP.md)

두 번째 학습 단위는 **수락 알림을 못 받아 UNKNOWN인 메시지를 같은 ID로 수동 재시도**하는 것이다. 기본 앱/서버에는 실패 주입이 없다. [학습 안내의 두 번째 단위](STUDY_GUIDE.md#두-번째-학습-단위-timeout이-서버-기록을-지우지는-않는다)를 따라 별도 테스트 프록시와 debug 학습 모드로만 실행한다.

세 번째 단위는 **프로세스 종료 뒤에도 같은 ID·본문·계정·방·상태를 복구**하는 Room outbox다. 로컬 저장 완료 뒤에만 POST하며, 새 프로세스의 남은 SENDING은 UNKNOWN으로 복구한다. 자동 재전송하지 않는다. [Room의 실패 경계와 다음 캐시·페이징 단계](STUDY_GUIDE.md#세-번째-학습-단위-room-outbox와-프로세스-종료)를 읽으며 실제 종료/재실행을 따라할 수 있다.

네 번째 단위는 **수신 기록을 계정·방별 Room에 보존**하는 것입니다. HTTP history, WebSocket snapshot/event, POST 수락이 같은 저장 경로로 합쳐집니다. 당시 DB Flow 읽기는 아래 다섯 번째 단위의 Room Paging으로 확장했습니다. 빈 snapshot으로 과거 캐시를 지우지 않고, 서버 실행 UUID로 재시작 뒤 sequence 재사용을 구분합니다. [직접 예측하고 테스트하기](CACHE_EXERCISE.md)로 먼저 확인하세요.

다섯 번째 단위는 **최신 20개 WS bootstrap + 배타적 before cursor + Room Paging**입니다. 과거 조회와 live 수신은 같은 캐시로 합치고, 실패한 과거 요청은 같은 cursor로 수동 재시도합니다. 전경 소켓은 Application의 ProcessLifecycleOwner가 관리하므로 Activity 재생성으로 끊기지 않습니다. 배경에서는 소켓을 닫습니다. 로컬 Push adapter 검사는 공통 저장 경로를 확인하며 실제 FCM 전달은 아래 여섯 번째 연결 검증까지 진행했습니다.

여섯 번째 단위는 **기준점 이후 연속 확인 지점을 Room에 보존하고 after20개씩 누락을 자동 보충**합니다. 높은 Push/WS 번호가 먼저 도착해도 빈 구간을 건너뛰지 않습니다. 중단된 commit 지점부터 재실행하며 before 과거 탐색과 구분합니다. FID 기반 Messaging SDK/service/worker는 준비했고, 기본 빌드는 Firebase 등록을 실행하지 않습니다. 사용자 승인 후 전용 프로젝트/dev.chatlab을 등록하고 emulator Alice의 실제 FID 등록·배경 data 수신·HTTP 복구를 확인했습니다. 기본 빌드는 외부 등록을 켜지 않습니다. [FCM 실행](FCM_SETUP.md)에 설정 보관 위치와 경계를 남겼습니다.

## 실행

기존 JDK 21, Android SDK 36/build-tools 36.0.0, `adb`와 ARM64 에뮬레이터가 필요하다. 프로젝트 의존성은 Google Maven·Maven Central·Gradle Plugin Portal에서 받는다. 시스템 도구를 자동 설치하지 않는다.

Android Studio에서 디렉터리를 열거나 로컬 `local.properties`에 **본인의** SDK 경로를 지정한다. 이 파일은 Git에서 제외된다.

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

```bash
./gradlew :server:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

관찰한 에뮬레이터 한 개를 실행한 뒤 실제 Room DB 검사를 추가로 실행한다. 테스트는 임시 DB만 만들고 제거하며 검사 자체는 실제 앱 DB를 직접 지우지 않는다. 다만 Android 테스트 도구가 종료 시 대상 APK를 제거할 수 있으므로 수동 데모는 테스트가 끝난 뒤 APK를 설치해 실행한다.

```bash
ANDROID_SERIAL=<observed-serial> ./gradlew -Pandroid.injected.device.serial=<observed-serial> \
  :app:connectedDebugAndroidTest
```

터미널 하나에서 서버를 유지한다.

```bash
./gradlew :server:run
```

설치돼 있는 AVD 하나를 Android Studio에서 시작한다. 이번 검증은 기존 Pixel_8a(API 34)를 `-read-only -no-snapshot`으로 실행했다. 기존 AVD를 그대로 검증하려면 다음처럼 실행할 수 있다(AVD 이름은 본인 환경에 맞춘다).

```bash
emulator -avd Pixel_8a -read-only -no-snapshot -no-audio -memory 2048 -cores 2
```

다른 터미널에서 기기 목록을 확인한 뒤 대상 serial만 선택한다. 아래 `emulator-5554`는 이번 검증에서 관찰한 값이다.

```bash
adb devices -l
adb -s emulator-5554 reverse tcp:8080 tcp:8080
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n dev.chatlab/.MainActivity
```

전체 검사가 끝난 뒤 APK를 설치합니다. 여러 AVD가 연결돼 있으면 위 검사 명령에도 serial을 반드시 지정합니다. 화면이 Alice·연결됨인지 확인하고, 별도 터미널에서 Bob peer를 실행한다.

```bash
./gradlew :server:demoClient --console=plain
```

`BOB_READY`가 나오면 **2분 이내** 앱에서 `ping`을 보낸다. Bob은 새 Alice 이벤트를 받은 뒤 `Bob reply: ping`을 HTTP로 전송한다. 앱에 응답이 보이고 콘솔에 `ROUND_TRIP_PASS`가 나오면 왕복 성공이다. 오래 걸려 timeout이 나면 peer 명령만 다시 실행한다.

`SENT`/“서버 수락”은 **표시한 서버 실행의 메모리 기록에 들어갔다**는 뜻이다. 영속 저장·상대 수신·읽음을 뜻하지 않는다. 서버를 종료하면 서버 기록과 중복 키 인덱스는 사라지지만 기기가 관찰해 저장한 캐시는 남는다. 캐시의 SENT는 과거 실행의 수락도 포함하며, 실행 ID와 sequence를 함께 표시한다.

`UNKNOWN` 행의 “같은 ID로 재시도”는 기존 ID·본문을 유지한다. 연결된 자신의 미확인 행만 재시도하며, 이미 WS로 수락을 확인했다면 HTTP가 timeout 나도 SENT를 유지한다.

## 직접 해볼 실험

서버를 실행한 상태에서 다음을 실행한다. 같은 `clientMessageId`·본문으로 두 번 보내도 같은 서버 ID와 sequence를 반환하고 기록은 하나만 늘어난다. 같은 ID에 다른 본문을 보내면 `409`를 반환한다.

```bash
bash scripts/idempotency-demo.sh
```

이 실험의 의미와 서버 재시작 후 보장이 사라지는 이유는 [학습 안내](STUDY_GUIDE.md)에 있다.

## 폴더

- `server`: 신원·방 검사, 메모리 store, HTTP/WS, 서버 테스트와 Bob peer.
- `app`: Compose/Paging 화면, 프로세스 전경 세션, 공통 repository, Room outbox·수신 캐시·페이지 키, sync cursor/hint, schema v1/v2/v3/v4와 실제 DB 검사.
- `scripts`: 로컬 재현 실험·검증 증거 대조.
- `evidence`(Git 제외): 이 Mac에서 만든 화면·로그·history·빌드 증거.

서버와 에뮬레이터를 멈출 때는 본인이 시작한 실행 세션만 종료한다. 외부 배포·방화벽 변경은 필요하지 않다.
