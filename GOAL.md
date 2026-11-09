# GOAL — VirtualApp-X modernization

> 살아있는 진행 문서. 작업 단계가 끝날 때마다 상태와 로그를 갱신한다.
> 마지막 갱신: 2026-11-09 (P3 완료, 빈두 Godot 구동)

## 최종 목표

Android 16(API 36)에서 **가상 공간 안에 앱을 설치하고 실행**하는 VirtualApp 호스트를 만든다.
첫 검증 대상은 **빈두(`kr.chat.anon.bindu`)**.

VirtualApp 기반 안드로이드 가상화 컨테이너를 내 요구에 맞게 수정하고, 내가 만든 APK 안에서 도깨비의세계 앱을 실행한다. 우선 컨테이너의 설치·실행 흐름과 앱 호환성을 구현하고, 필요한 변경 사항을 단계별로 기록한다.

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
| P1-c | 최소 호스트 앱(`host`) + 시험용 타깃(`hello`) 작성 | ✅ 완료 — 호스트가 Android 16에서 크래시 없이 시작, `VirtualCore.startup OK` (A2 충족) |
| P2 | Android 16 프레임워크 대응: hidden API 우회, AMS/ATMS/PM 훅, 앱 프로세스 기동 | ✅ 완료 — hello가 가상 프로세스(uid 10001, 데이터 `virtual/` 하위)에서 화면까지 뜸 |
| P3 | split APK 설치 파이프라인, arm64 라이브러리 추출 | ✅ 완료 — 빈두 base+split 4개 설치, arm64 .so 3개 추출 (A3 충족) |
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
- 2026-11-09 P1-c: `host` 모듈(최소 호스트, adb extras로 install/launch 제어)과 `hello` 모듈(시험용 타깃 앱) 추가. 호스트는 Z Flip 4(Android 16)에서 정상 시작. `app` 모듈 마이그레이션은 폐기하고 `host`로 대체.
- 2026-11-09 Play Protect: `targetSdk 28` 호스트를 **새로 설치**하면 "안전하지 않은 앱 차단됨 — Android 이전 버전에 맞게 개발됨" 대화상자가 뜸(`-r` 덮어쓰기에서는 안 뜸). 기기의 `min_supported_target_sdk`는 28이라 OS 경고가 아니라 Play Protect 판정. 호스트 `targetSdk`를 34로 올려 해결 확인(새로 설치해도 대화상자 없음). 이후 사용자가 기기 대화상자는 직접 눌러도 된다고 허용함.
- 2026-11-09 P2: 서버 프로세스 크래시 2건 수정 — (1) Android 13+에서 `IPackageManager` flags가 `long`이라 `(int) args[n]` 캐스팅 실패 → `helper/utils/NumArg.java`로 교체, (2) `DaemonService`의 빈 `startForeground`(Android 14+에서 SecurityException) 제거.
- 2026-11-09 P2: `PackageParser`(Android 11+ 구조 변경)를 대체하는 `server/pm/parser/ModernPackageParser.java` 추가 — `getPackageArchiveInfo` + 바이너리 `AndroidManifest.xml` 직접 파싱(intent-filter/uses-library/protected-broadcast). `VPackage`에 공개 타입 생성자 추가. **결과: hello 설치 성공, 런치 인텐트 정상 해석.**
- 2026-11-09 P2: `HCallbackStub`에 Android 9+ `EXECUTE_TRANSACTION`(ClientTransaction→LaunchActivityItem) 처리 추가, 미러 `servertransaction/*` 신규. `LAUNCH_ACTIVITY` 부재로 인한 `<clinit>` NPE 해소.
- 2026-11-09 P2: `InvocationStubManager`가 훅 하나의 실패로 전체가 죽지 않도록 격리(`VAX-INJECT` 태그로 실패 목록 출력). 현재 실패: `ClipBoardStub`, `WindowManagerStub`(sWindowManagerService), `NotificationManagerStub`(sService) 등 미러 null.
- 도구: `tools/run-test.sh <install-pkg|-> <launch-pkg|-> [대기초]` = 빌드→설치→실행→필터 로그. `MirrorDiag`: `am start -n io.vax.host/.MainActivity --ez diag true` 후 `logcat -s VAXDIAG`로 Android 16에서 null인 mirror 필드 목록(564개 중 343개 null, 구버전 전용 다수 포함).
- 알려진 미해결: `install result: success=false pkg=null`(실제로는 설치됨 → `InstallResult` 직렬화 확인 필요). 가상 프로세스에서 `WindowManagerGlobal`/`NotificationManager`/`ClipboardManager` 서비스 필드명이 Android 16에서 바뀜.
- 다음: (1) `VAX-INJECT` 실패 훅의 미러 보정 (2) 가상 프로세스 시작 후 `HelloActivity`가 뜨는지 확인 (3) `InstallResult` 직렬화 버그 (4) 이후 P3(split APK)로 빈두.
- 범위 메모: 가상 환경 탐지·무결성 검사·치트 방지 우회는 작업 범위가 아니다(사용자 문서의 `범위 밖` 섹션이 비어 있어도 유지). 도깨비의세계는 설치·실행 호환성까지만.
- 2026-11-09 P2 완료: 근본 원인은 **hidden API 예외가 전혀 없었던 것**(미러 343/564 null). `org.lsposed.hiddenapibypass:4.3`(Apache-2.0)을 `VirtualCore` static 초기화에서 `addHiddenApiExemptions("L")`로 적용 → `VAX-INJECT` 실패 0건.
- 2026-11-09 P2: 이어서 수정한 것 — (1) Android 14+는 쓰기 가능한 dex 로드를 거부 → 설치된 `base.apk`를 0444로 chmod, 이후 `InstallResult` success=true도 해소 (2) `hookAndroidVM`(옛 ART 네이티브 메서드 슬롯 스캔)은 API 30+에서 jmethodID가 불투명 인덱스라 SIGSEGV → API 30+에서는 건너뜀(getCallingUid/openDexFile/카메라/오디오 훅 미동작, 필요 시 재구현) (3) `libva++.so` 경로를 `nativeLibraryDir`로 (4) `ApplicationInfo.splitSourceDirs`에 base.apk를 넣던 원본 꼼수 제거(LoadedApk NPE) (5) `getContentProvider`가 Android 10+에서 callingPackage 인자를 가져 → 호스트 패키지로 치환, 이름 인덱스 2 (6) `IContentProvider`가 Android 11~12에서 AttributionSource 등으로 바뀜 → `ProviderHook`은 API 30+에서 패스스루 + AttributionSource를 호스트 uid/패키지로 재작성.
- 남은 경고(비치명): `IOUniformer` 후킹 시 `Too many memory mapping / cannot read memory map` (IO 리다이렉트 native 훅이 실제로 걸렸는지 미검증 → P4), `AutoFillManagerStub`/`SpecialComponentList` 내 WebView 조회 NPE.
- 2026-11-10 P3 완료: `installPackage(path)`가 디렉터리(`base.apk`+`split_*.apk`)를 받으면 모두 복사(0444)하고 `PackageSetting.splitApkPaths` 저장 → `ApplicationInfo.splitSourceDirs/splitNames` 채움. arm64 라이브러리는 숨은 `NativeLibraryHelper` 대신 zip 기반 추출기로 base+split 전체에서 ABI를 골라 추출(빈두: `libgodot_android.so`, `libwebrtc_native...so`, `libc++_shared.so`).
- 2026-11-10 P3 중 수정: `registerReceiver`에 `RECEIVER_EXPORTED` 필요(Android 14+), `IntentFilter.mActions`가 `ArraySet`, `package.ini` 재작성 전 삭제, `getVolumeList` 첫 인자는 uid가 아니라 userId.
- 2026-11-10 **A4 부분 충족**: 빈두가 VA 프로세스 슬롯(`io.vax.host:p0`)에서 GodotApp 시작 → `libgodot_android.so` 로드 → `OnGodotMainLoopStarted`까지 진행(OpenGL ES 3.2, Adreno 730). 직후 `AudioTrack` 스레드에서 libgodot 오디오 콜백이 SIGSEGV(SEGV_ACCERR, fault addr `0xb40000733b7ff000`).
- 2026-11-10 **대조 실험**: 기기에 네이티브로 설치된 원본 빈두를 `monkey`로 실행해도 **동일한 주소로 같은 SIGSEGV**가 남. 즉 이 크래시는 VA 문제가 아니라 빈두(Godot OpenSL 오디오) × 이 기기/OS(Android 16, 11/09 업데이트 후) 조합의 문제. 앱 수정/우회는 하지 않고 기록만 함. 확인 필요: OS 업데이트 이전에는 정상이었는지, 빈두 새 빌드가 있는지.

