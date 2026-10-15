# KTransfer 계획

파파고·구글 번역 앱을 대체하는 개인용 AI 통역 앱. 목표는 동시통역에 가까운 지연.

작성: 2026-10-15. 아래 수치와 제약은 이날 공식 문서에서 확인한 값이다. 모델이 preview라 바뀔 수 있다.

## 결정 사항

| 항목 | 결정 |
|---|---|
| 엔진 | Gemini Live API의 `gemini-3.5-live-translate-preview` (AI Studio 키) |
| 프레임워크 | Kotlin Multiplatform + Compose Multiplatform |
| 대상 | 1순위 안드로이드, 2순위 Windows PC(JVM 데스크톱). 아이폰은 Mac 확보 시 추가 (iOS 빌드에는 macOS·Xcode 필요) |
| 주 용도 | 여행 등에서 외국인과 마주 보고 대화 (대화 모드 우선) |
| 언어 | 한국어 ↔ 영어·일본어·중국어·베트남어 우선, 나머지 지원 언어는 목록으로 제공 |
| 작업 위치 | WSL `~/src/KTransfer` (ext4) |
| 저장소 | GitHub 공개 저장소 https://github.com/gim47656-ship-it/KTransfer (secret scanning·push protection 켬). API 키는 커밋하지 않는다 |

## 개발 환경

- 편집·빌드: WSL. JDK 21(`~/.local/opt/jdk-21`, Temurin)과 Android SDK(`~/Android/Sdk`: platform 36, build-tools 36.0.0, platform-tools)를 사용자 홈에 설치했다 (2026-10-15). 라이선스 동의는 Windows SDK `licenses` 기록을 복사했다.
- 폰 설치·로그: 폰은 Windows에 USB로 연결하고, WSL에서 Windows `adb.exe`(`C:\Users\kkmin\AppData\Local\Android\Sdk\platform-tools\adb.exe`, 37.0.0)를 불러 `adb.exe install "$(wslpath -w <apk>)"`로 설치한다. 무선 디버깅은 대안.
- Windows 데스크톱 산출물: Compose Desktop 패키징(msi/exe)은 대상 OS에서 빌드해야 한다. WSL에서는 개발·실행 확인을 JVM으로 하고, Windows 설치 파일은 GitHub Actions Windows 러너에서 만든다 (2단계).
- Windows 쪽에 이미 있는 Android Studio(JBR 21), SDK(platforms 36/36.1, build-tools 37)는 UI 디버깅이 필요할 때 보조로만 쓴다.

## 엔진 사실 (공식 문서 확인)

| 항목 | 내용 |
|---|---|
| 프로토콜 | WebSocket `BidiGenerateContent`, setup의 `generationConfig.translationConfig`. 전사 설정 `inputAudioTranscription`·`outputAudioTranscription`은 `setup` 바로 아래에 둔다 (가이드의 WebSocket 예제처럼 `generationConfig` 안에 두면 서버가 1007로 거절, 2026-10-15 실측) |
| 오디오 | 입력 16kHz 16bit mono PCM, 100ms 청크 / 출력 24kHz PCM 음성 + 원문·번역 전사 텍스트 |
| 언어 | 70+개, 한국어 `ko` 포함, 입력 언어 자동 감지 |
| 목표 언어 | 세션당 1개 고정. `echoTargetLanguage=false`면 이미 목표 언어인 입력에는 침묵 |
| 제약 | 오디오 입력만. 지시문·도구·텍스트 입력 불가 → 용어집·말투 지정 불가 |
| 세션 | 압축 없이 오디오 세션 15분, 연결 약 10분. GoAway 메시지 후 세션 재개로 이어 붙임 |
| 비용 (유료) | 입력 $0.0053/분, 출력 $0.0315/분 → 세션 1개 시간당 약 $2.2 |
| 무료 티어 | 있음. 단 입력이 구글 제품 개선에 사용됨 |
| 인증 | 배포 앱은 ephemeral token 권장 (`v1beta`, translationConfig 잠금/해제 가능) |
| 알려진 한계 | 긴 침묵 뒤 목소리·성별 흔들림, 비슷한 언어 감지 혼동, 배경음 일부 잔존 |

미확인: 번역 모델에서 세션 재개(session resumption)·컨텍스트 압축 지원 여부 → 0단계에서 확인.

## 설계

### 대화 모드: 세션 두 개로 자동 방향 전환

같은 마이크 오디오를 세션 A(목표 한국어)와 세션 B(목표 상대 언어)에 동시에 보낸다. 두 세션 모두 `echoTargetLanguage=false`.
내가 한국어로 말하면 A는 침묵하고 B만 통역한다. 상대가 말하면 반대로 된다. 방향 전환 버튼이 필요 없다.

비용은 입력이 두 배라 시간당 약 $2.5로 추정한다. 침묵 중에는 출력 과금이 거의 없다는 가정이며 실측 전까지 추정치다.

### 최대 리스크: 스피커 되먹임

스피커로 나간 번역 음성이 마이크로 다시 들어가면 반대 세션이 재통역해 루프가 생길 수 있다. 대책 후보:

1. 안드로이드 마이크를 `MediaRecorder.AudioSource.VOICE_COMMUNICATION` + `AcousticEchoCanceler`로 받아 하드웨어 에코 제거
2. 번역 음성 재생 중에는 반대 세션 입력을 막음 (반이중 게이팅)
3. 이어폰 사용

0단계 실측으로 고른다.

### 모듈 구성

`commonMain` (공유):
- Live API WebSocket 클라이언트 (Ktor client WebSockets): setup·오디오 청크 전송·응답 파싱·GoAway/재연결
- 세션 두 개 관리와 방향 판단, 자막 상태, 언어 선택
- Compose Multiplatform 화면: 대화·듣기·자막 모드, 설정
- 오디오 인터페이스(`expect`/`actual` 또는 인터페이스 주입): 마이크 PCM 스트림, PCM 재생

`androidMain`:
- `AudioRecord` 16kHz mono + `AcousticEchoCanceler`, `AudioTrack` 24kHz 재생
- 화면이 꺼져도 이어지는 포그라운드 서비스 (마이크 타입)
- 키 보관: Android Keystore 기반 암호화 저장

`desktopMain` (JVM, Windows):
- `javax.sound.sampled` 마이크·스피커 PCM
- 2단계: PC 시스템 소리 캡처 (회의·영상 듣기 모드). 방식은 그때 조사

키는 앱에 내장하지 않는다. 설정에서 입력해 기기에 저장한다 (개인용 BYOK). 공유가 필요해지면 ephemeral token 발급 서버를 추가한다.

### 화면과 모드

- 대화 모드 (1순위): 세션 두 개, 스피커 출력. 아래쪽에 내 자막, 위쪽에 상대용으로 180도 뒤집힌 자막
- 듣기 모드: 세션 한 개, 이어폰으로 강의·안내방송·영상 듣기 (PC에서는 시스템 소리 입력)
- 자막 모드: 음성 끄고 자막만

## 단계

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| 0. 검증 | AI Studio Live 화면에서 한↔영·일·중·베 품질 체감 → WSL에 JDK·Android SDK 설치 → 저장소와 KMP 골격(android + desktop) → 세션 1개 통역 → 세션 2개 동시 실행 → 실제 폰에서 스피커 루프 실측 → 세션 재개 지원 확인 | 발화 시작부터 첫 번역 음성까지 지연 실측값, 루프 대책 결정, 재개 지원 여부 |
| 1. MVP (안드로이드) | 대화 모드, 언어 선택, 양방향 자막, 자동 재연결, 재생 버퍼, 포그라운드 서비스 | 실제 폰에서 30분 대화 중 끊김 0회, 되먹임 루프 0회 |
| 2. 다듬기 + Windows | 블루투스 이어폰, 듣기 모드, 사용 시간·비용 표시, 대화 기록, Windows 데스크톱 빌드(CI)와 PC 소리 듣기 | 여행에서 파파고 대신 손이 가는 수준, PC에서 회의·영상 통역 동작 |
| 3. 확장 | 텍스트·사진 번역 (일반 Gemini Flash), 대화 요약, 전사 후처리로 용어 보정, Mac 확보 시 iOS | 필요한 것만 선택 |

## 참고

- Live translation 가이드: https://ai.google.dev/gemini-api/docs/live-api/live-translate
- 모델 페이지: https://ai.google.dev/gemini-api/docs/models/gemini-3.5-live-translate-preview
- 가격: https://ai.google.dev/gemini-api/docs/pricing
- 세션 관리: https://ai.google.dev/gemini-api/docs/live-api/session-management
- Ephemeral token: https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- 예제 저장소: https://github.com/google-gemini/gemini-live-api-examples
- AI Studio에서 바로 써 보기: https://aistudio.google.com/live?model=gemini-3.5-live-translate-preview
