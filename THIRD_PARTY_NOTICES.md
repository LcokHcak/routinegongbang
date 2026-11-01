# Third-party notices · 서드파티 고지

## 앱에 포함되는 라이브러리

| 라이브러리 | 버전 | 라이선스 |
|---|---|---|
| Kotlin standard library (JetBrains) | 2.0.0 | Apache License 2.0 |
| kotlinx.coroutines (JetBrains) | 1.8.1 | Apache License 2.0 |
| kotlinx.serialization (JetBrains) | 1.7.1 | Apache License 2.0 |
| AndroidX Core KTX | 1.13.1 | Apache License 2.0 |
| AndroidX Activity Compose | 1.9.0 | Apache License 2.0 |
| AndroidX Lifecycle Runtime Compose | 2.8.3 | Apache License 2.0 |
| Jetpack Compose (UI, Foundation, Material 3; BOM 2024.06.00) | 1.6.8 / Material 3 1.2.1 | Apache License 2.0 |
| Pleos Connect SDK: Vehicle, NaviHelper (42dot Inc.) | 2.3.2, 3.0.5 | 42dot Software Development Kit License Agreement |

Apache License 2.0 전문: https://www.apache.org/licenses/LICENSE-2.0

Pleos Connect SDK는 이 저장소에 포함되어 있지 않습니다. 빌드할 때 42dot이 공개한 Maven 저장소에서 받으며,
SDK 라이선스에 따라 SDK 자체를 복제 · 수정 · 재배포하지 않습니다.

## 빌드와 테스트에만 쓰는 도구

| 도구 | 라이선스 |
|---|---|
| Gradle Wrapper (`gradle/wrapper/gradle-wrapper.jar`) | Apache License 2.0 |
| Android Gradle Plugin 8.8.2 | Apache License 2.0 |
| JUnit 5.10.3 | Eclipse Public License 2.0 |

## 참고한 설계 (코드는 가져오지 않음)

| 출처 | 라이선스 | 참고한 것 |
|---|---|---|
| Home Assistant 자동화 | Apache License 2.0 | 트리거 · 조건 · 동작으로 나누는 루틴 모델 |
| Easymatic | MIT | 순수 Kotlin 로직과 Android 계층을 나누는 구조, 백그라운드 실행 서비스 구조 |
| Easer | GPL-3.0 | 자동화 앱의 화면 구성 (설계만 참고) |
| Pleos Vehicle SDK 공식 예제 앱 | — | 능력 조회 → 읽기 → 쓰기 → 구독의 호출 순서 |
