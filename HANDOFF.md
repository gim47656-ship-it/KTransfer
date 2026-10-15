# KTransfer HANDOFF

현재 단계: 0단계(검증) 진행 중. 계획과 설계 근거는 [PLAN.md](PLAN.md), 빌드·실행 절차는 [README.md](README.md).

## 남은 행동

| 항목 | 다음 행동 | 필요한 것 | 닫는 기준 |
|---|---|---|---|
| API 지연 실측 | `liveProbe`로 `ko.wav`→`en`, `en.wav`→`ko`를 각각 실행해 첫 번역 음성까지 지연을 잰다 (README 「지연 측정」) | Gemini API 키 (`local.properties`의 `gemini.apiKey` 또는 `GEMINI_API_KEY`) | 두 방향 모두 `first translated audio after speech start` 값과 번역 전사를 기록에 남김 |
| 세션 재개 지원 | `liveProbe ... --resumption`으로 `sessionResumptionUpdate` 수신 여부와 setup 거절 여부를 본다 | API 키 | `resumptionUpdates` 값 확인. 0이거나 거절되면 1단계 재연결 설계를 "재개 없이 새 세션"으로 확정 |
| 실제 폰 동작 | 디버그 APK를 폰에 설치해 대화 모드로 한↔영 통역, 화면 지연 수치 확인 | 폰 USB 연결(개발자 옵션·USB 디버깅), API 키 | 폰에서 두 방향 통역이 들리고 화면 지연 수치가 나옴 |
| 스피커 루프 실측 | 같은 폰에서 에코 제거 켬/끔 × 반이중 켬/끔 조합으로 30초씩 말해 보고, 자기 번역을 다시 통역하는지 본다 | 위와 같음 | 루프가 없는 조합을 골라 1단계 기본값으로 정함 |

0단계 검증이 끝나면 PLAN.md 「단계」의 1단계(MVP: 포그라운드 서비스, 자동 재연결 확정, 재생 버퍼)로 넘어간다.

## 경계

- API 키는 저장소·빌드 산출물에 넣지 않는다. 공개 저장소이며 GitHub secret scanning·push protection이 켜져 있다.
- 빌드 환경은 WSL 사용자 홈의 JDK 21과 Android SDK다 (README 「개발 환경」). Windows 쪽 Android Studio·SDK는 건드리지 않는다.
