# 0단계 골격: 툴체인·저장소·KMP 앱·Live API 클라이언트

RECORD:
DATE: 2026-10-15
SCOPE: phase0 scaffold, toolchain, live translate client, CI
PATHS: shared/, androidApp/, desktopApp/, .github/workflows/ci.yml, gradle/
STATUS: partial

## 결정

- 엔진은 `gemini-3.5-live-translate-preview`. 근거와 제약은 PLAN.md 「엔진 사실」.
- 플랫폼은 사용자 결정으로 PWA → 안드로이드 네이티브 → Flutter → Kotlin Multiplatform 순으로 바뀌어 KMP로 확정. 작업 위치도 사용자 결정으로 WSL `~/src/KTransfer`.
- 저장소는 사용자 결정으로 공개(CI 무료). 대신 키 비노출: `.gitignore`(local.properties·.env·keystore), 키를 빌드 산출물에 넣지 않음, GitHub secret scanning·push protection 켬.
- 구조는 JetBrains `Kotlin/KMP-App-Template`(2026-09-11 커밋)의 AGP 9 방식(`shared`는 `com.android.kotlin.multiplatform.library`, 앱은 별도 `androidApp` 모듈)을 따름.
- 버전: Kotlin 2.4.21, Compose Multiplatform 1.12.2, material3 1.12.0-alpha03(공식 템플릿과 같은 방식의 짝 버전), AGP 9.4.1, Gradle 9.8.1, Ktor 3.6.0. `compileSdk 37`은 최신 Compose·OkHttp AAR이 요구해서 올림. `targetSdk`는 36 유지.
- Live API 연결은 Firebase SDK 없이 Ktor WebSocket으로 직접. Firebase는 별도 프로젝트가 필요하고 `translationConfig` 지원이 확인되지 않음.
- 대화 모드는 세션 두 개(목표 ko·상대 언어, 둘 다 `echoTargetLanguage=false`)에 같은 마이크를 보냄.
- 0단계에는 포그라운드 서비스가 없어 `FLAG_KEEP_SCREEN_ON`으로 대신함. 포그라운드 서비스는 1단계.

## 실행

- WSL 툴체인: Temurin JDK 21.0.12.1 → `~/.local/opt/jdk-21`; Android cmdline-tools 23.0.0, platform-tools 37.0.1, platforms android-36·android-37.0, build-tools 36.0.0 → `~/Android/Sdk`. SDK 라이선스는 Windows SDK(`C:\Users\kkmin\AppData\Local\Android\Sdk\licenses`)의 기존 동의 기록을 복사.
- 저장소: `gh repo create`(비공개) 후 사용자 지시로 API(`PATCH private=false`, `security_and_analysis`)로 공개 전환·secret scanning·push protection 활성화.
- 시험 음성: Windows PowerShell `System.Speech`로 16kHz/16bit mono WAV 생성. 한국어 "안녕하세요. 이 근처에 맛있는 쌀국수 집이 있나요? 그리고 지하철역까지 걸어서 얼마나 걸리는지 알려 주세요."(Microsoft Heami), 영어 "Excuse me, could you tell me how to get to the central train station? I also need to buy a ticket for tomorrow morning."(Microsoft Zira). 위치는 `~/.cache/ktransfer-probe/ko.wav`·`en.wav`(저장소 밖). 다시 만들 때는 `SpeechSynthesizer.SetOutputToWaveFile(path, SpeechAudioFormatInfo(16000, Sixteen, Mono))`.

## 빌드 중 고친 것

- `LiveSession`의 큐 이름 `outgoing`이 Ktor `DefaultClientWebSocketSession.outgoing`에 가려져 `for (... in outgoing)`이 모호해짐 → `audioQueue`로 변경.
- `checkDebugAarMetadata`: Compose 1.12.x·OkHttp 5.5.0 AAR이 compileSdk 37 이상 요구 → android-37.0 설치, compileSdk 37.
- `Interpreter.runLane`이 `job` 대입 전에 시작되면 루프가 바로 끝나는 경쟁 → `currentCoroutineContext().isActive`로 판정.
- 실제 서버 규격: 가짜 키로 실제 엔드포인트에 연결해 보니, 인증 전에 setup 형식을 검사해 `1007 Invalid JSON payload received. Unknown name "inputAudioTranscription" at 'setup.generation_config'`로 닫음. 공식 Live Translate 가이드의 WebSocket 예제와 다른 부분이라 전사 설정을 `setup` 바로 아래로 옮김 → 이후 응답은 `1007 API key not valid`(형식 통과, 인증 단계). `sessionResumption`을 넣은 setup도 같은 응답이라 필드 이름은 유효함. 모델이 실제로 재개를 지원하는지는 진짜 키가 필요. 회귀 테스트 `transcriptionConfigSitsUnderSetupNotGenerationConfig` 추가.

## 교차검토 (OpenAI 계열, 2회)

API 키를 다루는 파일이라 커밋 전 교차검토 대상(`credentials-auth`). 지적 8건 모두 수정.

- 키 노출: 연결 오류 메시지·종료 사유에 키가 섞일 수 있음 → `LiveSession`이 밖으로 내보내는 메시지에서 키를 `***`로 가림. 가짜 키 실행 출력에서 키 문자열 0회 확인.
- setup 무응답 시 무한 대기 → 15초 감시 후 연결을 닫고 재시도로 넘김.
- 시작 실패 시 스피커·마이크 누수 → 부분 실패 시 연 것만 해제, 마이크는 시작 전에 보관.
- 중지·재시작 경쟁 → 실행 번호(`run`)를 화면 상태에 넣어 확인과 갱신을 한 번의 CAS로 묶음. 스피커 해제는 세션 작업 종료(`cancelAndJoin`) 뒤, 정리 작업끼리는 사슬로 이어 이전 정리를 기다림.
- 재시도 횟수 6회 → 5회(`>=`).

커밋 전체가 표본 검사(10%)에 뽑혀 나머지 파일도 검토(2회). 지적 8건은 모두 `AndroidPlatform.kt`이고 모두 수정.

- 통화 모드 종료 시 원래 상태 복원: 이전 mode·speakerphone 저장 후 복원. API 31+는 앱별 요청인 `setCommunicationDevice`를 `clearCommunicationDevice()`로 철회.
- 마이크 초기화·해제 예외 안전: 녹음기·AEC를 먼저 보관하고 실패 시 정리 후 다시 던짐. 해제는 `finally`, 스레드 대기 인터럽트에도 정리 진행.
- 마이크 읽기 오류(`read <= 0`)는 캡처 종료로 처리해 빈 루프 방지.
- 재생은 20ms 조각으로 쓰고 조각마다 세대 확인 → `clear()`가 진행 중인 쓰기도 끊음. `release()`는 쓰기 스레드 종료를 기다리고, 시간 초과 시 `track.stop()`으로 막힌 쓰기를 풀어 줌.
- `gradle-wrapper.jar`는 바이너리라 검토 제외(Gradle 9.8.1 `gradle wrapper`가 생성, 배포판 sha256 고정).

## 검증

- `./gradlew :shared:jvmTest :androidApp:assembleDebug :desktopApp:assemble` 종료 코드 0. `LiveProtocolTest` 4건 통과(tests=4, failures=0, errors=0). 수정 후 `Interpreter.class`와 APK가 소스보다 새로 만들어진 것 확인.
- 실제 엔드포인트: setup 형식 통과까지 확인(가짜 키로 `API key not valid` 응답).
- 미검증: 진짜 키로 통역·지연, 폰 동작(폰 미연결), 스피커 루프, 세션 재개 지원. HANDOFF.md 「남은 행동」.
