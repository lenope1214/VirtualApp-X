# GOAL

> VirtualApp 포크 개인 프로젝트 — 2017년 오픈소스 코드를 Android 16까지 현대화하고,
> 가상 공간(클론/샌드박스) 안에서 앱을 실행하는 것을 목표로 한다.

## 프로젝트 개요

- 베이스: `asLody/VirtualApp` (2017) → 포크 `lenope1214/VirtualApp-X`
- 호스트 앱: `io.vax.host`
- 핵심 기능: 게스트 앱을 UI 오버레이가 아니라 **가상 공간 안에서 실제 실행**(clone/sandbox)
- 상용 체인지로그 기능은 상용 코드 없이 독립 재구현

## 최종 목표

<!-- 이 섹션은 직접 편집 -->

- 최종: 도깨비의세계(`com.kakaogames.dokkaebi`)를 내 APK 안에서 실행
- 1차 검증 타깃: 빈두(`kr.chat.anon.bindu`)

## 테스트 환경

- 기기: Samsung Galaxy Z Flip 4 (SM-F721N), Android 16 / API 36, arm64 + 32-bit
- 툴체인: JDK17, Android SDK (API 36, NDK 27.3.13750724) — 셸 기본 JAVA_HOME이 jdk8이므로
  `tools/run-test.sh` 사용(JDK17 강제) 또는 빌드 전 JAVA_HOME/ANDROID_SDK_ROOT export
- 반복 루프: `tools/run-test.sh <install-pkg|-> <launch-pkg|-> [wait]` — 호스트 빌드·설치·실행,
  로그를 VAX/VAX-INJECT/HELLO/AndroidRuntime 태그로 필터

## 마일스톤 / 진행 상황

- [x] P0–P3: 기본 클론/샌드박스 동작, hidden-API 우회(LSPosed 라이브러리)가 근본 수정
- [x] hello 앱 샌드박스 실행
- [x] 빈두(5개 split APK, 에셋 팩 포함) 설치·실행 — Godot 메인 루프 진입
      (이후 libgodot AudioTrack 콜백에서 크래시; 네이티브 원본도 동일 기기/OS에서 같은 지점
      크래시 → VA 버그 아님, 앱 패치 안 함)
- [x] 샌드박스 투명성 개선 — 게스트에게 VA 흔적을 숨겨 더 네이티브에 가까운 런타임 제공
      (`/proc/self/{maps,cmdline,status}` 필터, ApplicationInfo 경로 정규화, adb_enabled 은닉).
      hello 회귀 통과, 게스트가 `/data/user/0/<pkg>/...` 깨끗한 경로를 봄
- [ ] IO redirect 정밀 검증 (IOUniformer 'Too many memory mapping' 경고, hookAndroidVM API30+ 스킵)
- [ ] 안정성/회귀 정비

## 로그

- 2026-11-10 — 샌드박스 투명성 1차 구현 커밋(`d4dacdd2`). 네이티브 proc 필터링 + Java 측
  경로 정규화/adb 은닉. `nativeLibraryDir`은 동적 링커가 직접 쓰는 필드라 정규화에서 제외
  (정규화 시 `System.loadLibrary` 실패하는 회귀 확인·수정). arm64 빌드·hello 회귀 검증.
- 2026-11-10 — proc 필터링 **기능 검증**. hello에 proc-probe 추가(maps/cmdline/status를
  게스트 내부에서 읽어 확인). 결과: maps 2590줄 중 VA-leak **0**, cmdline/comm/status Name
  모두 게스트명으로 위장, TracerPid 0. 빈두(5-split Godot) 회귀: 동일 Godot surface 지점까지
  도달(알려진 오디오 크래시, sourceDir 정규화 회귀 없음). proc 가로채기 커버리지 확장
  (environ의 LD_PRELOAD/V_* 은닉, mounts/mountinfo, comm) — 빌드·검증 완료.
- 2026-11-10 — 도깨비 탐지 로그(`app_shareds`) 확인: 트리거된 단일 벡터 = **ADB_ENABLED**
  (LifeCycle 마커는 미트리거). adb가 실제로 켜져 있어서 발생. Java Settings 훅이 못 잡음
  → LIAPP이 네이티브로 adb 상태 읽는 것으로 추정. 다음: USB 디버깅 끄고 폰에서 직접 터치
  실행해 ADB_ENABLED가 유일 게이트인지 확인(사용자 진행).
