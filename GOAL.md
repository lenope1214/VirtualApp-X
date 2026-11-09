# GOAL — VirtualApp-X modernization

> 살아있는 진행 문서. 작업 단계가 끝날 때마다 상태와 로그를 갱신한다.
> 마지막 갱신: 2026-11-09

## 최종 목표

Android 16(API 36)에서 **가상 공간 안에 앱을 설치하고 실행**하는 VirtualApp 호스트를 만든다.
첫 검증 대상은 **빈두(`kr.chat.anon.bindu`)**.

## 합격 기준 (Acceptance)

| # | 기준 | 확인 방법 |
|---|---|---|
| A1 | `lib`이 arm64-v8a로 빌드된다 | `./gradlew :lib:assembleDebug` 성공 |
| A2 | 호스트 앱이 Z Flip 4(Android 16)에 설치되어 크래시 없이 시작한다 | `adb install` + `logcat` |
| A3 | 빈두 split APK(base + arm64 + asset pack + 언어/해상도)가 VA 내부에 설치된다 | VA 설치 API 성공, 내부 저장소에 파일 존재 |
| A4 | 빈두가 VA 안에서 실행되어 첫 화면(Godot)이 뜬다 | 스크린샷 + `logcat`에 Godot 초기화 로그, 프로세스가 VA 프로세스 슬롯에서 동작 |
| A5 | 빈두 기본 동작(터치 입력, 네트워크 연결)이 정상이다 | 수동 확인 + `logcat` |
| A6 | 원본 빈두(외부 설치본)에 영향이 없다 | 원본 앱 데이터/설치 상태 변화 없음 |

## 범위 밖 (하지 않는 것)

- 대상 앱의 가상 환경 탐지·무결성 검사·치트 방지를 우회하는 작업
- 상용 VirtualApp 코드의 사용 (업데이트 이력은 기능 체크리스트로만 참고)
- 사용자 승인 없는 `git push` 및 외부 서비스로의 업로드

## 작업 규칙

- 브랜치: `my-main`. 단계마다 커밋. 푸시는 사용자 승인 후에만.
- 도구는 리포지토리 밖(`E:\devtools`)에 둔다.
- 막히면 원인을 이 문서의 "로그"에 기록하고, 추측으로 넘어가지 않는다.

## 마일스톤

| 단계 | 내용 | 상태 |
|---|---|---|
| P0 | 현대 툴체인으로 `lib` 빌드 (JDK17/AGP8.10/Gradle8.11/NDK27) | ✅ 완료 (armeabi-v7a/x86 기준) |
| P1-a | `lib` native 코드 arm64 컴파일 (legacy syscall 대응) | ✅ 완료 |
| P1-b | arm64 inline hook 엔진 도입 (And64InlineHook, MIT) | 🔄 컴파일만 완료, **기기 동작 검증 필요** |
| P1-c | 최소 호스트 앱 신규 작성 (AndroidX, 설치/실행 버튼만) | ⬜ |
| P2 | Android 16 프레임워크 대응: hidden API 우회, AMS/ATMS/PM 훅, 앱 프로세스 기동 | ⬜ |
| P3 | split APK 설치 파이프라인, arm64 라이브러리 추출 | ⬜ |
| P4 | 저장소/IO 리다이렉션 (scoped storage 경로) | ⬜ |
| P5 | 빈두 실행 안정화 (Godot/Vulkan, WebRTC, 알림) | ⬜ |
| P6 | (필요 시) Google 서비스 | ⬜ |

## 기술 메모 (사실 기록)

- 테스트 기기: SM-F721N, Android 16 / API 36, arm64 + 32비트 지원
- 빈두: targetSdk 36 / minSdk 24, **arm64-v8a 전용**, split 5개, Godot(`libgodot_android.so`) + WebRTC native
- Android 14 이상은 낮은 targetSdk 앱 설치를 거부 → 호스트 targetSdk 28로 상향
- 원본 VA(2017)는 Android 8 기준: hidden API, PackageParser, ActivityManagerNative 등 대부분 재작업 필요

## 로그

- 2026-11-09 P0: 빌드 복구. `abs()` 모호성, fbjni `-Werror`, 제네릭 삼항 추론, AIDL 옵션 수정. 커밋 824969af.
- 2026-11-09 P1-a: arm64 빌드 시도 → `IOUniformer.cpp`에서 `__NR_chmod` 등 legacy syscall 상수 없음(arm64는 `*at` 계열만 존재).
- 2026-11-09 P1-a: `Foundation/LegacySyscall.h` 추가. arm64에 없는 legacy syscall(open/chmod/stat64/rename 등)을 `*at` 계열로 변환. 모던 API(>20)에서는 `*at` 훅만 설치되므로 legacy 훅은 실행되지 않음.
- 2026-11-09 P1-b: `Substrate`에는 arm64 구현이 없음 → `And64InlineHook`(MIT, 서드파티)을 `jni/And64InlineHook/`에 포함하고 `MSHookFunction`을 연결. 이 엔진은 명령어 재배치 지원 범위가 제한적이라 libc 훅 대상 함수에서 실제 동작 확인이 필요(미검증).
- 알려진 위험: `Foundation/IOUniformer.cpp`의 `fstat` 훅 시그니처가 원본부터 잘못되어 있음(fd 대신 path). 설치 대상이 아니므로 방치, 필요 시 정리.
