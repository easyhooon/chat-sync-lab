# Chat Sync Lab

Kotlin 개발자를 위한 작은 실시간 채팅 학습 프로젝트. **Ktor 서버 + Android Compose/Ktor Client(OkHttp 엔진)**로 Alice/Bob 두 테스트 신원이 한 방에서 텍스트를 주고받는다.

첫 목표는 실제 네트워크 경로와 메시지 정합성을 관찰하는 것이다. 서버 하나와 Android app 하나로 구성하며 영속 DB·실제 인증·자동 재연결·읽음 기능은 아직 없다. 서버는 `127.0.0.1:8080`에만 바인딩한다.

- [범위·화면 상태·JSON 계약](SCOPE.md)
- [Android 관점으로 읽는 데이터 경로와 실패 시나리오](STUDY_GUIDE.md)
- [실제 검증 결과와 한계](VERIFICATION.md)

## 실행

기존 JDK 21, Android SDK 36/build-tools 36.0.0, `adb`와 ARM64 에뮬레이터가 필요하다. 프로젝트 의존성은 Google Maven·Maven Central·Gradle Plugin Portal에서 받는다. 시스템 도구를 자동 설치하지 않는다.

Android Studio에서 디렉터리를 열거나 로컬 `local.properties`에 **본인의** SDK 경로를 지정한다. 이 파일은 Git에서 제외된다.

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

```bash
./gradlew :server:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
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

화면이 Alice·연결됨인지 확인하고, 별도 터미널에서 Bob peer를 실행한다.

```bash
./gradlew :server:demoClient --console=plain
```

`BOB_READY`가 나오면 **2분 이내** 앱에서 `ping`을 보낸다. Bob은 새 Alice 이벤트를 받은 뒤 `Bob reply: ping`을 HTTP로 전송한다. 앱에 응답이 보이고 콘솔에 `ROUND_TRIP_PASS`가 나오면 왕복 성공이다. 오래 걸려 timeout이 나면 peer 명령만 다시 실행한다.

`SENT`/“서버 수락”은 **현재 서버 프로세스의 메모리 기록에 들어갔다**는 뜻이다. 영속 저장·상대 수신·읽음을 뜻하지 않는다. 서버를 종료하면 기록과 중복 키 인덱스가 사라진다.

## 직접 해볼 실험

서버를 실행한 상태에서 다음을 실행한다. 같은 `clientMessageId`·본문으로 두 번 보내도 같은 서버 ID와 sequence를 반환하고 기록은 하나만 늘어난다. 같은 ID에 다른 본문을 보내면 `409`를 반환한다.

```bash
bash scripts/idempotency-demo.sh
```

이 실험의 의미와 서버 재시작 후 보장이 사라지는 이유는 [학습 안내](STUDY_GUIDE.md)에 있다.

## 폴더

- `server`: 신원·방 검사, 메모리 store, HTTP/WS, 서버 테스트와 Bob peer.
- `app`: 화면, Ktor Client, 상태 병합과 오류 표시 테스트.
- `scripts`: 로컬 재현 실험·검증 증거 대조.
- `evidence`(Git 제외): 이 Mac에서 만든 화면·로그·history·빌드 증거.

서버와 에뮬레이터를 멈출 때는 본인이 시작한 실행 세션만 종료한다. 외부 배포·방화벽 변경은 필요하지 않다.
