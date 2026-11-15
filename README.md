# CueTone

동승자가 듣는 음악을 다음 회전 방향으로 살짝 기울여(좌우 7:3), 음성이나 알림음 없이 몸이 미리 움직임을 준비하게 해 차멀미를 줄이는 Pleos Connect 차량용 앱입니다.

## 주요 기능
- **회전 예고**: 내비 회전 안내(NaviHelper) + 같은 방향 방향지시등(Vehicle SDK)이 일치하고, 회전 지점까지 30m 이내가 되면 음악을 회전 방향으로 7:3으로 기울임 → 회전 후 가운데로 복귀
- **차선변경 예고**: 가까운 회전 없이 방향지시등만 켜지면 그 방향으로 약 2초간 기울였다 복귀
- **정차 처리**: 시속 10km 미만이면 대기, 회전 지점 30m 이내에서 다시 출발하는 순간 예고
- **안전장치**: 경로 재탐색·회전 통과 시 즉시 가운데 복귀
- **백그라운드 동작**: 음악 재생과 예고는 포그라운드 서비스에서 동작하여 내비 화면이 떠 있거나 앱 화면이 가려져도 계속 작동
- **가상 주행 모드(테스트)**: 시속 36km 정속 주행을 가정해 내비 경로상 다음 교차로까지 남은 거리를 줄여가며 예고 흐름을 재현

## 시연 영상
> **이어폰(스테레오)으로 들어야** 음악이 좌우로 기우는 것을 확인할 수 있습니다.

| 영상 | 내용 |
|---|---|
| [좌회전 시연](demo/CueTone_demo_left.mp4) (22초) | Virtual Console에서 왼쪽 방향지시등 → 음악이 왼쪽으로 기울어 `왼쪽 70% \| 오른쪽 30%` → 끄면 가운데 복귀 |
| [우회전 시연](demo/CueTone_demo_right.mp4) (1분 26초) | 오른쪽 방향지시등에 따른 예고와 복귀 |

## APK 위치
`apk/Newbie_CueTone_release.apk`

## 실행 방법
### APK로 바로 실행
1. Pleos Connect 에뮬레이터를 실행합니다.
2. APK를 설치합니다.
   ```
   adb install apk/Newbie_CueTone_release.apk
   ```
3. 앱을 실행하고 **음악 재생**을 누릅니다.
4. 내비게이션에서 목적지를 설정한 뒤, 앱 화면의 **가상 주행 (테스트)** 를 켭니다.
5. Pleos Connect Virtual Console의 *Convenience → Turn Signal State* 에서 방향지시등을 켜면, 회전 방향으로 음악이 기울고 화면에 `왼쪽 70% | 오른쪽 30%` 처럼 좌우 음량이 표시됩니다.
6. 좌우 차이를 확인하려면 **이어폰(스테레오)** 착용을 권장합니다.

### 소스에서 빌드
1. Android Studio로 이 저장소를 엽니다.
2. Gradle Sync (Pleos SDK는 `settings.gradle.kts`에 등록된 Pleos 공식 Maven 저장소에서 자동으로 받습니다).
3. 실행 대상으로 Pleos Connect 에뮬레이터를 선택하고 Run.

> 프로젝트 경로에 한글이 포함되면 빌드가 실패할 수 있습니다.

## 테스트 환경 및 필요한 설정
| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 (API 34) |
| 보조 도구 | Pleos Connect Virtual Console (방향지시등·주행 상태 조작) |
| 사용 SDK | Pleos Vehicle SDK 2.3.2 (방향지시등), Pleos NaviHelper 3.0.5 (회전 안내·경로 상태) |
| 권한 | `pleos.car.permission.CAR_LIGHTS`, `pleos.car.permission.NAVI_ROUTE`, 포그라운드 서비스(미디어 재생), 알림 |
| minSdk / targetSdk | 28 / 37 |
| 오디오 | 앱 자체 음원 재생 (`res/raw/calm_music.mp3`), `MediaPlayer.setVolume`으로 좌우 음량 조절 |

### 참고
- 기본 에뮬레이터에서는 방향지시등 조작이 불가하여 Virtual Console을 사용합니다.
- Virtual Console의 주행이 에뮬레이터 내비 위치와 연동되지 않아, 시연·검증은 **가상 주행 모드**로 진행합니다.
- `distractionOptimized`가 적용되어 있으나, 개발용으로 직접 설치한 앱은 차량 OS 정책상 주행 중 화면이 가려질 수 있습니다. 이 경우에도 음악과 방향 예고는 백그라운드 서비스에서 계속 동작합니다.

## 코드 구조
| 파일 | 역할 |
|---|---|
| `TurnCueEngine.kt` | 예고 판단 엔진 (회전 30m, 정차 10km/h, 차선변경, 재탐색·통과 처리) |
| `PleosNaviClient.kt` | NaviHelper로 회전 안내(TBT)·경로 상태 수신 |
| `PleosTurnSignalClient.kt` | Vehicle SDK로 방향지시등 상태 수신 |
| `DrivingSimulator.kt` | 가상 주행 (시속 36km 정속) |
| `MulmiPlaybackService.kt` | 음악 재생·좌우 음량 조절 포그라운드 서비스 |
| `MainActivity.kt` | 화면 (깜빡이 자동 연동·가상 주행 스위치, 다음 방향, 좌우 음량 표시) |
