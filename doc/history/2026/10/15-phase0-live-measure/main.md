# 0단계 실측: 진짜 키로 지연·전사, 폰 설치

RECORD:
DATE: 2026-10-15
SCOPE: phase0 live API measurement, phone install
PATHS: shared/src/commonMain/kotlin/com/kys/ktransfer/live/LiveProtocol.kt, shared/src/commonMain/kotlin/com/kys/ktransfer/Interpreter.kt, desktopApp/src/main/kotlin/com/kys/ktransfer/probe/LiveProbe.kt, README.md
STATUS: partial

## 키

- 사용자 지시로 AI Studio(`gim47656@gmail.com`, 프로젝트 `Default Gemini Project`)에서 브라우저 릴레이로 키 `KTransfer`를 만들어 `local.properties`의 `gemini.apiKey`에 저장. 결제 미연결이라 무료 티어(입력이 구글 제품 개선에 쓰임).
- 2026년 키 형식은 `AIza…`가 아니라 `AQ.`으로 시작하는 53자. 화면에는 가려져 있고, 「클립보드에 키 복사」 버튼의 `navigator.clipboard.writeText`를 페이지 안에서 가로채 파일로 바로 저장. 먼저 누른 복사로 Windows 클립보드에 들어간 키는 비움.

## 지연 실측 (`liveProbe`, Windows 음성 합성 WAV)

| 방향 | 말 시작 → 첫 입력 전사 | 말 시작 → 첫 번역 음성 | 말 끝 → 마지막 번역 목소리 | 번역 목소리 길이 / 입력 |
|---|---|---|---|---|
| ko → en (1회차) | 2682 ms | 2854 ms | (무음 포함 7870 ms) | (무음 포함 17000 / 10095 ms) |
| ko → en (2회차, 목소리만 셈) | 2622 ms | 2813 ms | **2034 ms** | 8750 / 10095 ms |
| en → ko | 2806 ms | 3007 ms | (무음 포함 7928 ms) | (무음 포함 14750 / 8110 ms) |

- setupComplete는 연결 후 0.9~1.2초.
- 번역 전사: ko→en "Hello. Is there a good rice noodle place around here? And how far is it to the subway station? Could you tell me how long it takes to walk there?" / en→ko "실례합니다, 시내 중심가로 어떻게 가는지 알려주시겠어요? 그리고 내일 아침 표도 사야 해요." ("central train station"을 "시내 중심가"로 옮긴 오역 1건).
- 서버는 말이 끝난 뒤에도 약 2초 간격으로 무음 오디오를 보냄. 그래서 처음 잰 "마지막 번역 음성"과 총길이가 부풀었음 → 측정 도구가 RMS 300 초과 조각만 따로 세게 바꿈.

## 세션 재개

- `--resumption`으로 요청하면 `sessionResumptionUpdate`(`resumable=true`, 핸들 있음)가 10회 옴. 요청하지 않은 세션에서도 9회 옴. 핸들 발급은 지원됨. 핸들로 실제 이어 붙이기는 아직 안 해 봄(1단계 재연결에서 확인).

## 기타 서버 메시지

- 오디오·전사·재개 외에 오는 것: `text` 없는 `serverContent.inputTranscription`·`outputTranscription`, 빈 `serverContent`, `usageMetadata`. 정보성이라 무시.
- 파서가 `Other`에 `serverContent.<하위 키>`를 담도록 바꿈. 앱 로그는 같은 키 묶음을 세션마다 한 번만 남김(이전에는 0.5초마다 찍혀 화면 로그를 덮었을 것).

## 폰

- 갤럭시 S24+(`SM-S926N`, Android 16)를 무선 디버깅으로 연결(Tailscale 주소 `100.90.45.34`). `adb pair` 후 `adb connect`.
- APK 설치(`-g`로 마이크 권한 허용), 키는 `run-as`와 표준 입력으로 앱 설정 파일에 넣음(README 「폰에 설치」).
- 첫 `am start` 뒤 화면 앞에는 런처가 있었음(프로세스는 살아 있음). 다시 `am start`로 앞으로 가져와 화면 확인: 키 입력란 채워짐, 대화 모드, 상대 영어, 에코 제거 켬, 반이중·재개 끔, 잘림 없음.
- 흠: 흰 배경에 상태바 아이콘이 밝은색이라 시계가 거의 안 보임. 1단계 UI에서 고침.

## 교차검토 (OpenAI 계열, 1회)

- 지적 1건: RMS를 정수로 잘라 300~301 사이 조각이 무음으로 빠짐 → `Double` 그대로 비교. 수정 후 `:shared:jvmTest :desktopApp:assemble` 종료 코드 0.

## 검증

- `./gradlew :shared:jvmTest :androidApp:assembleDebug` 종료 코드 0, `:desktopApp:assemble` 종료 코드 0.
- 미검증: 폰에서 실제 통역 음성·지연, 스피커 루프. HANDOFF.md 「남은 행동」.
