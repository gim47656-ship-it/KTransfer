# KTransfer

Gemini Live API의 동시통역 모델(`gemini-3.5-live-translate-preview`)로 만드는 개인용 실시간 통역 앱. Kotlin Multiplatform + Compose Multiplatform, 대상은 안드로이드와 PC(JVM).

설계와 단계는 [PLAN.md](PLAN.md), 현재 상태와 다음 행동은 [HANDOFF.md](HANDOFF.md).

## 구성

| 모듈 | 내용 |
|---|---|
| `shared` | 공통: Live API WebSocket 클라이언트(`live/`), 통역 엔진(`Interpreter`), Compose 화면(`App`). 플랫폼별 오디오·설정 저장은 `androidMain`·`jvmMain` |
| `androidApp` | 안드로이드 앱 진입점 |
| `desktopApp` | PC 앱 진입점과 지연 측정 도구(`probe/LiveProbe.kt`) |

## API 키

키는 저장소에 넣지 않는다.

- 안드로이드: 앱 첫 화면에 입력하면 앱 전용 저장소에 보관된다.
- PC 앱: 화면에 입력하거나 `GEMINI_API_KEY` 환경 변수를 둔다.
- 지연 측정 도구: `GEMINI_API_KEY` 환경 변수, 또는 저장소 루트 `local.properties`(git 제외)에 `gemini.apiKey=...`.

## 개발 환경 (WSL)

- JDK 21: `~/.local/opt/jdk-21`
- Android SDK: `~/Android/Sdk` (`local.properties`의 `sdk.dir`)

```bash
export JAVA_HOME=~/.local/opt/jdk-21 ANDROID_HOME=~/Android/Sdk
./gradlew :shared:jvmTest :androidApp:assembleDebug :desktopApp:assemble
```

CI(`.github/workflows/ci.yml`)도 같은 작업을 돌리고 디버그 APK를 `ktransfer-debug-apk` 산출물로 올린다.

## 폰에 설치

WSL에서 Windows `adb.exe`를 쓴다. 폰은 USB 디버깅이나 무선 디버깅으로 연결한다. 무선 디버깅은 폰의 「페어링 코드로 기기 페어링」 화면에 나오는 주소·코드로 한 번 페어링한 뒤, 무선 디버깅 첫 화면의 주소로 연결한다.

```bash
ADB=/mnt/c/Users/kkmin/AppData/Local/Android/Sdk/platform-tools/adb.exe
$ADB pair <페어링 주소:포트> <6자리 코드>   # 무선 디버깅 처음 한 번
$ADB connect <연결 주소:포트>
$ADB devices
# adb.exe가 읽을 수 있게 APK를 Windows 임시 폴더로 복사해 설치
mkdir -p /mnt/c/Users/kkmin/AppData/Local/Temp/ktransfer
cp androidApp/build/outputs/apk/debug/androidApp-debug.apk /mnt/c/Users/kkmin/AppData/Local/Temp/ktransfer/
$ADB install -r -g 'C:\Users\kkmin\AppData\Local\Temp\ktransfer\androidApp-debug.apk'
```

API 키는 앱 화면 입력란에 넣는다. 개발 중에는 디버그 앱의 설정 파일에 표준 입력으로 써 넣을 수 있다(키가 명령줄에 남지 않음, 앱을 멈춘 상태에서).

```bash
$ADB shell am force-stop com.kys.ktransfer
printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"apiKey\">%s</string>\n</map>\n" \
  "$(grep '^gemini.apiKey=' local.properties | cut -d= -f2-)" \
  | $ADB shell "run-as com.kys.ktransfer sh -c 'mkdir -p shared_prefs && cat > shared_prefs/ktransfer.xml'"
```

## 지연 측정

WAV 파일을 실시간 속도로 흘려보내고 첫 입력 전사·첫 번역 음성이 도착한 시각을 출력한다. 입력은 1초 무음 → 음성 → 꼬리 무음(기본 8초) 순서다. 서버는 말이 끝난 뒤에도 무음 오디오를 보내므로, 실제 지연은 `last voiced audio`(RMS 300 초과 조각만 셈)를 본다.

```bash
./gradlew :desktopApp:liveProbe -PprobeArgs="<wav> <목표언어> [--resumption] [--out <wav>] [--tail <초>]"
# 예: 한국어 음성을 영어로
./gradlew :desktopApp:liveProbe -PprobeArgs="$HOME/.cache/ktransfer-probe/ko.wav en --out /tmp/ko-en.wav"
```

시험 음성은 Windows 음성 합성(Microsoft Heami·Zira)으로 만든 16kHz mono WAV다. 만드는 방법은 `doc/history/2026/10/15-phase0-scaffold/main.md`에 있다.

## 앱 화면의 시험 옵션 (0단계용)

- 에코 제거(통화 경로): 안드로이드에서 `VOICE_COMMUNICATION` 마이크, 하드웨어 `AcousticEchoCanceler`, 통화용 재생 경로를 쓰고 스피커로 출력한다.
- 반이중: 번역 음성이 재생되는 동안 모든 세션에 무음을 보낸다.
- 세션 재개 요청: setup에 `sessionResumption`을 넣는다(이 모델의 지원 여부 확인용).
- 지연 수치: 마이크 음량으로 말 시작을 감지해, 첫 번역 음성 도착까지 잰 근사치다.
