# Chat Sync Lab

Kotlin/Ktor 서버와 Android Compose 앱으로 Alice/Bob 두 테스트 신원이 `demo` 방에서 텍스트를 주고받는 로컬 학습 프로젝트다. HTTP 전송·WebSocket 수신·Room outbox/수신 캐시·Paging·누락 복구의 데이터 경로를 관찰한다.

**기본 채팅은 Mac의 로컬 서버로 실행한다. AWS 연동·배포, Firebase 프로젝트/설정, 결제 연결, 서비스계정 JSON이 필요하지 않다.** 서버는 `127.0.0.1:8080`에만 바인딩한다. 실제 로그인·읽음·서버 영속 DB·자동 reconnect 백오프는 없다.

| 실행할 부분 | 필요한 것 |
|---|---|
| Ktor 서버·HTTP/WS 테스트 | JDK 21, Git, 프로젝트 Gradle Wrapper. HTTP 확인에 curl |
| Android 기본 채팅 | 위 환경 + Android SDK 36/build-tools 36.0.0/platform-tools, API 26 이상 기기 또는 에뮬레이터 |
| 선택적인 FID/FCM 배경 Push | 위 환경 + 기존 승인된 Firebase 설정·Play services 기기·발송 경로. [FCM_SETUP](FCM_SETUP.md) 참고 |

기본 빌드에도 Messaging 라이브러리 의존성은 있지만 FCM 등록은 꺼져 있다. 아래 명령은 `-PchatFcmEnabled=false`로 기본 모드를 명시한다. 최초 의존성 다운로드에는 인터넷이 필요하며 Google Maven·Maven Central·Gradle Plugin Portal을 사용한다. 별도 Gradle 설치나 서버 DB/Docker는 필요하지 않다.

## 실행

### 1. 새 checkout과 JDK 확인

새 디렉터리에서 다음을 실행한다. 이미 checkout이 있다면 해당 저장소 루트로 이동해 JDK 확인부터 진행한다.

```bash
git clone https://github.com/easyhooon/chat-sync-lab.git
cd chat-sync-lab
java -version
./gradlew --version
```

JDK 21이 필요하다. 이 Mac에서 확인한 환경은 Temurin 21.0.11, Gradle Wrapper 9.2.1이다. Mac에 JDK 21이 설치되어 있지만 다른 Java가 선택됐다면 현재 터미널에서 다음을 사용한다.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
java -version
```

서버만 실행할 때는 Android SDK 경로·`local.properties`·`app/google-services.json`이 필요하지 않다.

### 2. 터미널 A: 로컬 Ktor 서버 시작

저장소 루트에서 실행하고 터미널을 유지한다. `server/build/install/...`은 처음부터 존재하지 않으므로 새 checkout의 시작 명령은 Gradle task다.

```bash
./gradlew -PchatFcmEnabled=false :server:run --console=plain
```

`Responding at http://127.0.0.1:8080`이 나오면 준비됐다. 이 명령은 서버가 살아 있는 동안 계속 실행된다. Kotlin 코드를 수정했다면 서버를 종료한 뒤 같은 명령으로 다시 시작한다.

### 3. 터미널 B: health와 기록 확인

동일 저장소 루트의 다른 터미널에서 실행한다. `--noproxy '*'`는 이 로컬 요청을 시스템 HTTP proxy로 보내지 않도록 한다.

```bash
curl --noproxy '*' --fail --silent --show-error \
  http://127.0.0.1:8080/health
```

기대 응답은 `{"status":"ok"}`다. health에는 신원 헤더가 필요하지 않다. 대화방 API에는 개발용 테스트 신원을 넣는다.

```bash
curl --noproxy '*' --fail --silent --show-error \
  -H 'X-Test-User: alice' \
  http://127.0.0.1:8080/rooms/demo/messages
```

새 서버는 `messages=[]`, `highWatermark=0`이다. 이후 조회는 기본 최신20개 페이지이며 전체 history가 아니다. 헤더 없는 방 요청은401, 접근 불가 방은403이다. `X-Test-User`는 로컬 테스트용이며 제품 인증을 대신하지 않는다.

Android 실행 전 Bob 텍스트 전송으로 서버를 확인할 수 있다.

```bash
curl --noproxy '*' --fail --silent --show-error \
  -H 'X-Test-User: bob' -H 'Content-Type: application/json' \
  -d '{"clientMessageId":"11111111-1111-4111-8111-111111111111","text":"README local smoke"}' \
  http://127.0.0.1:8080/rooms/demo/messages
```

Message JSON을 받는다. 첫 전송은201, 같은 ID/본문 재실행은200과 같은 서버 ID·sequence다. 같은 ID에 다른 본문은409다. 기본 앱을 Alice로 열면 최신 기록에서 이 Bob 메시지를 확인할 수 있다.

### 4. Android SDK와 기본 APK 빌드

Android Studio의 SDK Manager에서 SDK 36/build-tools 36.0.0/platform-tools와 사용할 시스템 이미지를 준비한다. ARM Mac에는 해당 ABI의 이미지를 사용한다. 기본 채팅에 Play services는 필수가 아니며 FCM 검증에는 필요하다.

다른 터미널에서 **본인의 SDK 경로**를 지정한다. 아래는 Mac 기본 경로 예시다. Android Studio가 생성한 ignored `local.properties`의 `sdk.dir`를 사용해도 된다. 서버 전용 실행에는 이 단계가 필요하지 않다.

```bash
export CHAT_ANDROID_SDK="$HOME/Library/Android/sdk"
export ANDROID_HOME="$CHAT_ANDROID_SDK"
export PATH="$CHAT_ANDROID_SDK/platform-tools:$CHAT_ANDROID_SDK/emulator:$PATH"
./gradlew -PchatFcmEnabled=false :app:assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`다. Android Studio와 같은 SDK의 adb를 사용한다. Homebrew의 다른 adb가 먼저 선택되지 않았는지 확인할 수 있다.

```bash
command -v adb
adb version
emulator -list-avds
adb devices -l
```

### 5. 에뮬레이터 한 개 연결

이미 실행 중인 대상이 있으면 사용한다. 새 AVD는 Android Studio Device Manager에서 시작하거나, 위 목록에 있는 **본인의 AVD 이름**으로 실행한다. 영상 등 다른 작업의 에뮬레이터와 동시 실행할 자원이 부족하면 그 작업이 끝난 뒤 시작한다.

```bash
# Pixel_8a는 이 Mac의 예시 이름이다. 자신의 목록에 있는 이름으로 바꾼다.
emulator -avd Pixel_8a -read-only -no-snapshot -no-audio -memory 2048 -cores 2
```

새 터미널에서는 위 SDK/PATH 설정도 적용한다. `adb devices -l`에서 상태가 `device`인 대상 serial을 선택하고 모든 기기 명령에 명시한다. 아래 `emulator-5554`는 예시이며 실제 목록과 대조해 바꾼다.

```bash
adb devices -l
export CHAT_ANDROID_SERIAL=emulator-5554
adb -s "$CHAT_ANDROID_SERIAL" shell getprop sys.boot_completed
adb -s "$CHAT_ANDROID_SERIAL" reverse tcp:8080 tcp:8080
adb -s "$CHAT_ANDROID_SERIAL" reverse --list
adb -s "$CHAT_ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$CHAT_ANDROID_SERIAL" shell am start -n dev.chatlab/.MainActivity
```

부팅 값은1, 설치는 `Success`, 화면은 Alice·연결됨이 기대 결과다. 앱의 `127.0.0.1:8080`을 `adb reverse`가 Mac의 같은 포트로 전달한다. Mac에서 health가 성공해도 reverse가 없으면 앱 연결은 실패한다. 기본 앱은 debug HTTP를 허용하며 위 실행에는 `ack_loss_lab`/`fcm_test_account` extra를 넣지 않는다.

### 6. Android ↔ Bob 실시간 왕복

앱이 연결된 뒤 또 다른 터미널에서 저장소 루트의 Bob peer를 실행한다.

```bash
./gradlew -PchatFcmEnabled=false :server:demoClient --console=plain
```

`BOB_READY` 이후 **2분 이내** Android Alice가 `ping`을 보낸다. Bob은 새 Alice 이벤트를 WS로 받고 `Bob reply: ping`을 HTTP로 보낸다. Android 응답 표시와 peer의 `ROUND_TRIP_PASS`를 함께 확인한다. timeout이면 peer 명령만 다시 실행한다. 신원 Chip 전환은 현재 화면 계정을 바꾸는 로컬 도구이며 서버의 실제 로그인 기능이 아니다.

## 종료·재시작과 저장 경계

서버를 실행한 터미널 A에서 **Ctrl+C**로 자신의 서버를 종료한다. 새 연결이 거절되는지 확인한다.

```bash
curl --noproxy '*' --connect-timeout 2 --fail --silent --show-error \
  http://127.0.0.1:8080/health
```

서버가 종료됐다면 curl 연결 실패가 기대 결과다. 재시작은 같은 명령이다.

```bash
./gradlew -PchatFcmEnabled=false :server:run --console=plain
```

health 성공 뒤 대화방 조회를 다시 하면 새 `serverInstanceId`, 빈 `messages`, `highWatermark=0`이다. 실행 중인 앱은 필요하면 **다시 연결**을 누른다. 실제 신원 변경 없이 새 서버 실행과 이전 캐시를 구분한다.

| 종료/변경 | 남는 것 |
|---|---|
| Ktor 서버 종료/재시작 | 서버 메시지·idempotency 인덱스는 사라지고 실행 UUID/sequence가 새로 시작한다 |
| 같은 기기의 앱 process 종료/재실행 | Room outbox·관찰한 수신 캐시·before/after 키는 남는다. 이전 SENDING은 UNKNOWN으로 복구하며 자동 POST 재전송은 없다 |
| 서버 재시작 후 앱 복귀 | 이전 실행의 캐시는 보존하고 새 실행을 별도 namespace로 저장한다. 이전 서버에서 못 받은 기록을 새 서버가 복원하지 못한다 |
| read-only AVD 종료 | 임시 기기 데이터가 다음 AVD 실행에도 남는다고 보장하지 않는다. 일반 앱 process 재시작과 다른 경계다 |
| 앱 데이터 삭제/삭제 설치 | Room 보존 범위 밖이다. 캐시를 초기화하려고 `pm clear`나 uninstall을 기본 절차로 사용하지 않는다 |

`SENT`/“서버 수락”은 표시한 서버 실행의 메모리 기록에 들어갔다는 뜻이다. 영속 서버 저장·상대 수신·읽음은 아니다. Room은 기기가 실제 관찰한 기록을 저장하며 서버의 완전한 history를 대신하지 않는다. UNKNOWN 수동 retry는 원래 ID/본문을 유지하고 이미 수락된 receipt를 늦은 HTTP 실패가 되돌리지 않는다.

앱만 중지하거나 자신의 reverse만 해제할 때는 선택한 serial에 실행한다.

```bash
adb -s "$CHAT_ANDROID_SERIAL" shell am force-stop dev.chatlab
adb -s "$CHAT_ANDROID_SERIAL" reverse --remove tcp:8080
# 다시 연결할 때:
adb -s "$CHAT_ANDROID_SERIAL" reverse tcp:8080 tcp:8080
adb -s "$CHAT_ANDROID_SERIAL" shell am start -n dev.chatlab/.MainActivity
```

본인이 시작한 에뮬레이터를 완전히 종료할 때만 `adb -s "$CHAT_ANDROID_SERIAL" emu kill`을 사용한다. 다른 앱/AVD를 종료하지 않는다. force-stop은 FCM wake-up 실험과 다른 경계다.

## 문제 해결

| 관찰한 증상 | 확인할 것 |
|---|---|
| Java/Gradle 오류 | `java -version`, `./gradlew --version`에서 JDK21 확인. 시스템 Gradle 대신 저장소 `./gradlew` 사용 |
| SDK location not found | Android 빌드 터미널의 ANDROID_HOME 또는 ignored local.properties의 sdk.dir 확인. server만 실행할 때 Android task를 함께 요청하지 않음 |
| Connection refused/health 실패 | 서버 터미널의 시작 로그와8080 포트 확인. 서버 시작 전에 curl했다면 준비 후 다시 실행 |
| Address already in use | Mac에서 `lsof -nP -iTCP:8080 -sTCP:LISTEN`으로 소유자 확인. 코드 포트는8080 고정이며 다른 프로세스를 임의 종료하지 않음 |
| Mac health는 성공, 앱 연결 실패 | 같은 SDK의 adb·선택 serial·부팅1·reverse --list 확인 후 reverse 재등록/다시 연결 |
| device offline/no devices/multiple devices | 기기 목록을 관찰하고 연결된 `device` serial을 명시. APK 설치와 모든 adb 명령에 `-s` 사용 |
| 401/403 | 방 HTTP/WS의 X-Test-User가 alice/bob인지, 방이 demo인지 확인 |
| 409 CURSOR_EXPIRED | 서버 실행이 바뀐 과거/after cursor다. 앱에서 다시 연결해 새 실행의 bootstrap을 확인 |
| 기록이 재시작 뒤 화면에 남음 | 앱 Room의 관찰 캐시일 수 있다. 서버 GET의 새 실행/빈 history와 앱의 표시 실행 ID를 구분 |
| 과거/누락 복구 실패 | 받은 기록은 보존된다. 서버와 reverse를 확인하고 표시된 retry/다시 연결 사용 |
| Room 기기 검사 뒤 Activity를 찾지 못함 | instrumentation 종료 시 대상 APK가 제거될 수 있다. 검사가 모두 끝난 뒤 debug APK를 다시 설치하고 수동 데모 진행 |

## 선택 사항: 기존 FID/FCM 연결

위 로컬 채팅 실행에는 Firebase 결제·서비스계정 JSON·AWS 설정이 필요하지 않다. FCM은 앱이 배경일 때 hint를 받고 같은 로컬 서버에 HTTP로 복구하는 선택 경로다. Ktor 서버 자체는 여전히 Mac loopback에서 실행하며 AWS sender로 배포되지 않았다.

사용자 승인 후 전용 프로젝트/dev.chatlab/emulator Alice로 실제 FID 등록·단일 data 전송·새 배경 process 수신을 검증했다. 기본 빌드는 꺼져 있고, opt-in은 승인된 ignored `app/google-services.json`과 명시 설정이 필요하다. 설정 보관·SDK 등록 완료 조건·발송 권한·실제 검사·서비스계정 JSON 미구성은 [FCM_SETUP](FCM_SETUP.md)에 있다. FID/키/토큰/설정 파일을 README·Git·채팅에 붙여넣지 않는다.

현재 Ktor 서버에는 지속 FCM sender나 발송 REST endpoint가 없다. 로컬 기본 채팅을 띄웠다고 외부 Push가 전송되지는 않는다. 기존 승인 대상의 테스트 발송은 FCM_SETUP의 범위와 기존 권한을 확인한 뒤 별도로 수행한다. 이번 README 명령 검증에는 새 프로젝트·결제·자격증명·FCM 전송이 포함되지 않는다.

## 검사와 학습 자료

기본 서버 검사와 Android 빌드는 다음과 같다. 기기 없이 수행할 수 있다.

```bash
./gradlew -PchatFcmEnabled=false :server:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

실제 Room/Paging 기기 검사는 사용 가능한 한 기기를 관찰한 후 두 serial 제한을 함께 지정한다. 영상 등 다른 기기 작업과 겹치지 않게 실행한다.

```bash
ANDROID_SERIAL="$CHAT_ANDROID_SERIAL" ./gradlew -PchatFcmEnabled=false \
  -Pandroid.injected.device.serial="$CHAT_ANDROID_SERIAL" :app:connectedDebugAndroidTest
```

같은 ID 재전송 실험에는 Python3가 추가로 필요하다. 최신 page 개수로 증가를 대조하는 스크립트이므로 **새로 시작한 서버의 기록이20개 미만일 때** 실행한다. 이 실험을 위해 종료할 때도 본인이 시작한 서버만 대상으로 한다.

```bash
bash scripts/idempotency-demo.sh
```

- [범위·화면 상태·JSON 계약](SCOPE.md)
- [Android 개발자 관점의 데이터 경로·실패 시나리오](STUDY_GUIDE.md)
- [실제 검증 결과와 이번 README 명령의 검증 범위](VERIFICATION.md)
- [프로세스 종료·outbox 실습](OUTBOX_EXERCISE.md)
- [수신 캐시·늦은 snapshot 실습](CACHE_EXERCISE.md)
- [before cursor·Room Paging 계약](PAGING_CONTRACT.md), [과거 조회·live·스크롤 실습](PAGING_EXERCISE.md)
- [전경 소켓·배경 Push의 경계](BACKGROUND_CONTRACT.md)
- [연속 확인 지점·bounded after 계약](CATCH_UP_CONTRACT.md), [누락·중단·이어받기 실습](CATCH_UP_EXERCISE.md)
- [FID 연결·운영 migration과 이번 실험의 차이](FCM_SETUP.md)

## 폴더

- `server`: 테스트 신원/방 검사, 메모리 store, HTTP/WS, 검사와 Bob peer.
- `app`: Compose/Paging 화면, 프로세스 전경 세션, 공통 repository, Room outbox·캐시·cursor, schema v1–v4.
- `scripts`: 로컬 실험과 증거 대조.
- `evidence`(Git 제외): 이 Mac의 빌드·화면·로그·DB·history 증거. 새 checkout에 포함되지 않는다.
