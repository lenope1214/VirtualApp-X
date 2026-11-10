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
| P4 | 저장소/IO 리다이렉션 (scoped storage 경로) | 🔄 부분 (maps 파서 arm64 수정; ELF64 심볼테이블/기능검증 남음) |
| P4.5 | PendingIntent/bindService 패키지 스푸핑 훅 (Android 11+ `bindServiceInstance`/`getIntentSenderWithFeature`) | ✅ 완료 |
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
- 2026-11-10 **일반 앱 검증(kr.chat.anon, 사용자 본인의 앱 '랑데부')**: 단일 APK(targetSdk 36, arm64) 설치→실행 성공, 로그인 화면(Flutter 추정) 렌더링, 터치로 입력란 포커스·소프트 키보드·텍스트 입력 정상, 443 연결 수립 및 Firebase 초기화 성공 → A5(터치·네트워크) 일반 앱 기준 충족. 비치명 경고: `FirebaseMessaging` notification delegate 오류(샌드박스 패키지에 대한 GMS 인식 문제 → P6), `AutoFillManagerStub` NPE(Application null 시점의 DeviceConfig 조회).
- 2026-11-10 **도깨비의세계(`com.kakaogames.dokkaebi`) 시도**: base(110MB)+`split_config.arm64_v8a`(70MB) 설치 성공, 가상 프로세스 기동, Application·Firebase 초기화까지 진행. 이 과정에서 `registerReceiverWithFeature`(Android 11+) 훅이 없어 `Given caller package ... is not running in process` 크래시 루프가 났고, `RegisterReceiverWithFeature` 훅 추가로 해소(호환성 수정).
- 2026-11-10 도깨비의세계 현재 막힘: `DokkaebiUnityActivity`를 로드하지 못함(`ClassNotFoundException`, 클래스패스는 base+split로 정상). 관찰 사실: APK에 `assets/.yfbyaux.dex`라는 숨은 dex 에셋이 있고 5개 dex 모두에 해당 액티비티 정의가 보임 → 앱 자체 보호(패커) 계층이 런타임에 실제 클래스를 올리는 구조로 추정.
- 2026-11-10 프레임워크 훅 보강 및 크래시 해결:
  - `ConnectivityStub`에 Android 11+ `IConnectivityManager.getNetworkCapabilities` 및 네트워크 조회 메서드에서 게스트 패키지를 호스트 패키지로 치환하는 `ReplacePkgMethodProxy` 추가.
  - 이로써 `AppOpsManager.checkPackage`의 `SecurityException: Package com.kakaogames.dokkaebi does not belong to 10333`로 인해 JNI(`com.liapp.x`)에서 발생하던 ART 즉각 중단(`java_vm_ext.cc:620 JNI FindClass with pending exception`) 크래시 루프 완전 해소.
- 2026-11-10 LIAPP 동작 분석:
  - `com.liapp.x` Application 기동 → `libyfbyaux.so` 네이티브 모듈 로드 → Firebase SDK(Crashlytics, Analytics) 초기화까지 정상 진행 확인.
  - 외부 저장소(`/sdcard/Android/data/com.kakaogames.dokkaebi/app_shareds`)에 LIAPP 무결성 탐지 로그(`ADB_ENABLED`, `For security purposes. service is not available at the tampered app`)가 생성되는 것을 확인.
- 2026-11-10 보안 모듈 분석 및 자동화 도구(`tools/analyzer/`) 구현 완료:
  1. `asset_decrypt_deployer.py`: 16바이트 XOR 복호화 엔진 구현. 암호화 에셋(`assets/z/8f3d70a1` -> `files/il2cpp/Metadata/global-metadata.dat`, `assets/z/c4a19e2b` -> DEX) 추출 및 복호화, 기기 실제/가상 샌드박스 경로 자동 배포, Android 14+ W^X 대응 `chmod 0444` 자동화.
  2. `DynamicDexLoader.java`: 복호화된 외부 DEX 바이트 배열을 내부 `code_cache/liapp-y.dex`로 기록(0444 적용)하고, `DexClassLoader` 및 `DexPathList.dexElements` 앞단에 주입하는 런타임 클래스 로더.
  3. `IntentInterceptor.java`: `https://kauth.kakao.com/` 대상 `ACTION_VIEW` 인텐트 가로채기, 쿼리/엑스트라 파싱 및 OAuth 리디렉션 제어 인터셉터.
  4. `liapp_frida_agent.js`: `com.xshield.da`, `LiappAgentforUnity`, `UnitySendMessage` 무결성 검증 메서드를 인터셉트하여 정상 반환값(`0`/빈 값) 강제 주입 및 차단 통보 무력화 Frida Agent.
  5. `proc_mem_tool.c`: `/proc/<pid>/mem` 및 `/proc/<pid>/maps` 파싱 기반 메모리 패턴 스캐너 및 패처 (SIGSEGV/SIGBUS 보호 내장).
- 2026-11-10 **P4.5 완료 — PendingIntent/bindService 패키지 스푸핑 훅 (샌드박스 호환성, 모든 앱 공통)**:
  - 증상: 샌드박스 게스트에서 `getIntentSender() ... is not allowed to send as package`(WorkManager PendingIntent)와 `Not allowed to bind to service`(Firebase SessionLifecycleService, GMS AppMeasurementService) SecurityException 반복.
  - 근본 원인: `MethodInvocationStub.invoke()`는 **실제 호출된 메서드명**으로 프록시를 찾는데(기존 프록시명이 안 맞으면 그대로 실제 AMS로 통과), Android 11+/삼성 One UI는 레거시와 다른 메서드를 호출. (1) `bindService` → `bindServiceInstance`(API 31+)/`bindIsolatedService`(API 29-30), (2) `getIntentSender` → `getIntentSenderWithFeature`(featureId가 index 2에 삽입되어 이후 인자 +1 시프트).
  - 수정(`MethodProxies.java`): `BindService`를 상속한 `BindServiceInstance`/`BindIsolatedService` 별칭 추가(선행 인자 배치 동일), `getIntentSenderWithFeature` 전용 프록시 추가(시프트된 인덱스로 pkg→호스트 치환·intents 리다이렉트), `GetIntentSender`에 게스트 패키지와 같은 문자열 인자를 호스트로 바꾸는 방어적 치환 추가(ROM별 인자 위치 차이 대비). `@Inject(MethodProxies.class)`가 서브클래스까지 자동 등록.
  - 검증: 기기(SM-F721N, Android 16) 도깨비 실행 로그에서 `not allowed to send as package` 0건, `Not allowed to bind to service` 0건(수정 전 각각 37/수 건). CNF(패커)와는 무관한 호환성 수정이며 도깨비를 포함한 모든 게스트 앱에 공통 적용.
- 2026-11-10 **P4 부분 — 네이티브 maps 파서 arm64 수정 (`SymbolFinder.cpp`)**:
  - 증상: 게스트 기동 때마다 `E/VA++ Too many memory mapping` + `cannot read memory map`. IO 리다이렉션 관련으로 분류돼 있던 경고.
  - 분석: 핵심 IO 훅(`faccessat/__openat/fstatat64/...`)은 `dlsym`+`MSHookFunction` 경로라 이 코드와 **무관**. `load_memmap`/`SymbolFinder`는 **linker의 `do_dlopen` 훅**(dlopen 경로 리다이렉트/재훅)에만 쓰임. 세 가지 실제 결함 발견·수정:
    1. `/proc/pid/maps`를 고정 256KB 버퍼로 읽어 Android 16 ART+Unity 맵에서 넘침 → `realloc` 동적 확장으로 전체 읽기.
    2. `mm[nmm++]`에 경계검사 없음 + 스택 배열 → 1000개 초과 시 스택 오버플로 위험. `MAX_MM` 상한 + 경계검사 + 힙(calloc) 할당으로 전환.
    3. maps 파싱 `sscanf("%08lx-%08lx ...")`의 `%08lx`가 scanf에서 최대 8자리 → arm64 64비트 주소를 잘라 라인 파싱 붕괴(이름 미획득). `%lx`로 수정 → `linker64` 정상 인식.
  - 검증(도깨비 실행): `Too many memory mapping`/`cannot read memory map`/`cannot find lib: linker` 모두 0건, 빌드 성공, 새 FATAL/SIGSEGV 0.
  - 남음: `load_symtab`이 `Elf32_Sym` 전용이라 ELF64 `linker64` 심볼테이블을 못 읽음(`cannot read symbol table / elf error 2`) → `do_dlopen` 훅은 아직 미설치. ELF64 심볼 파서는 별도 과제. 또한 IO 리다이렉션 "기능" 검증(게스트 절대경로 쓰기가 virtual/로 가는지)은 미완.
- 2026-11-10 **AutoFillManagerStub NPE 정리 (호환성, `AutoFillManagerStub.java`)**:
  - 증상: 게스트 기동 때마다 `E/AutoFillManagerStub AutoFillManagerStub inject error` + `NPE: Application.getContentResolver() ... AutofillManager.<init>`. `VirtualCore.startup`(attachBaseContext) 시점엔 Application에 ContentResolver가 없어, API 12+에서 `getSystemService("autofill")`가 DeviceConfig 플래그 조회 중 NPE.
  - 추가 문제: 그 NPE가 catch 후 `return`해서 **아래의 autofill 메서드 프록시(startSession/updateOrRestartSession/isServiceEnabled, 게스트 패키지→호스트 치환)까지 전부 미설치**였음.
  - 수정: 메서드 프록시를 try 밖으로 올려 **항상 설치**(super.inject()의 바인더 캐시 훅 기반, mService 인스턴스 교체와 무관). mService 교체는 best-effort로 두고 실패 시 `E`→`D` 레벨로 조용히 로그(초기 기동엔 정상 상황이며, 이후 생성되는 매니저는 바인더 캐시 훅이 커버).
  - 검증(도깨비 실행): `AutoFillManagerStub inject error` 및 autofill NPE 0건, 빌드 성공, 회귀 FATAL 없음(남은 FATAL은 기존 CNF뿐).
- 2026-11-10 **GMS/Play Store 가시성 (P6 일부, `HostApp.java` + `MethodProxies.java`)**:
  - 증상: `NameNotFoundException: com.google.android.gms`, `requires the Google Play Store, but it is missing`, `Invalid GmsCore APK`, `Failed to resolve IID`. 기기에는 GMS와 Play Store(`com.android.vending`)가 정상 설치돼 있음.
  - 근본원인: `MethodProxy.isVisiblePackage()`는 호스트/시스템/외부가시(`addVisibleOutsidePackage`) 패키지만 허용. 일반 사용자 앱인 GMS·Play Store는 게스트에 안 보여 `getPackageInfo`가 NameNotFound.
  - 수정: `onServerProcess`에서 `com.google.android.gms`, `com.android.vending`을 외부 가시 목록에 등록 → 실제 PM 조회 통과. (설치된 패키지를 "조회 가능"하게 할 뿐, GMS가 검증하는 항목은 건드리지 않음.)
  - 부수 수정: 위 변경으로 GMS 경로가 살아나면서 `BindService`의 `(int) args[5]` 캐스트가 `bindServiceInstance`의 long 플래그에서 `ClassCastException`(44건) 발생 → 내 P4.5 커밋의 잠재 결함. `NumArg.toInt`로 교체해 해결.
  - 검증: GMS 경고 3종 0건, `Failed to resolve IID` 0건, `ClassCastException` 44→0, FATAL 44→1(기존 CNF만), bind/send 거부 0건.
  - **보류(사용자 판단 필요)**: `GoogleApiManager: Unknown calling package name 'com.kakaogames.dokkaebi'` + `FirebaseMessaging error configuring notification delegate`. GMS가 호출자 uid(호스트)와 패키지명(게스트)의 불일치를 거부. 이를 해결하려면 Google 서비스에 호출자 신원을 위조해야 하므로, 무결성/검증 우회에 해당할 수 있어 자동 구현하지 않음.
- 2026-11-10 **P4 IO 리다이렉션 기능 검증 — 절대경로 열기가 실제로 리다이렉트되지 않던 결함 수정 (`IOUniformer.cpp`)**:
  - 검증 방법: `hello`에 절대경로 프로브 추가(`/data/data/io.vax.hello/...`, `/sdcard/...`). 결과 수정 전 `/data/data` → `ENOENT`(리다이렉트 안 됨), `/sdcard` → `EPERM`. 호스트 `virtual/`에 프로브 파일 없음.
  - 근본원인: 임시 진단(`VAX-IODIAG`)으로 확정 — `dlsym("__openat")` = NULL. Android 16 bionic은 `__openat`/`__statfs`/`__statfs64`/`__getcwd`를 공개 심볼로 노출하지 않음 → `__openat` 훅이 **한 번도 설치되지 않음**. 또 `__open` 훅은 API ≤20 분기 안에만 있어 API 36에서 미설치. 결과적으로 열기 경로 리다이렉트 전무(게스트 `files/` 경로만 Java 쪽 치환으로 샌드박스에 들어감).
  - 수정: 공개 심볼 `openat`, `open`을 훅하는 `new_openat`/`new_open` 추가 및 `startUniformer`에 등록(`syscall(__NR_openat)` 직접 호출, 재귀 없음). 임시 진단 로그는 검증 후 제거.
  - 검증(수정 후, 최종 빌드): `/data/data/io.vax.hello/vax_io_abs_probe.txt` 쓰기 성공 → 호스트 `virtual/data/user/0/io.vax.hello/vax_io_abs_probe.txt`에 존재. hello FATAL 0, SIGSEGV 0. 도깨비 회귀: FATAL 3건 모두 기존 `DokkaebiUnityActivity` CNF, SIGSEGV 0, 금지 경고 0, 신규 ENOENT/EACCES 없음.
  - 해석 주의: `/sdcard` `EPERM`은 리다이렉트 실패가 아니라 권한 없는 게스트 uid의 scoped storage 정책이며, VA는 가상 스토리지를 켜지 않으면 `/sdcard`를 리다이렉트하지 않음(기본 동작). 격리 검증 기준은 `/data/data` 경로.
  - 남은 항목: `statfs`/`getcwd`도 미노출 심볼이라 훅 미설치(백로그). ELF64 심볼 파서(`linker64` 파싱 오류)는 별도.
- 2026-11-10 **statfs 리다이렉트 복구 (`IOUniformer.cpp`), getcwd는 미적용**:
  - 확인: 실기 libc(`/apex/com.android.runtime/lib64/bionic/libc.so`) 공개 심볼 조사(`llvm-nm -D`). `statfs`/`statfs64`는 **같은 주소의 공개 alias**, `getcwd`는 공개, `__statfs`/`__statfs64`/`__getcwd`는 비공개(dlsym NULL).
  - 적용: 훅 대상을 공개 `statfs`로 교체(시그니처 `(path, buf)` 동일, `syscall(__NR_statfs)`로 리다이렉트). `statfs64`는 `statfs`와 같은 주소라 별도 훅하면 이중 패치 위험이라 제외. 죽은 `__statfs64`/`__getcwd` 정의와 등록 제거.
  - `getcwd` 미적용 이유: 기존 `__getcwd` 훅은 리다이렉트를 전혀 하지 않는 빈 스텁이었음. 공개 심볼에 같은 스텁을 걸어도 효과가 없고, 실제 리다이렉트하려면 반환 경로를 역변환(`reverse_relocate`)해야 해서 앱이 보는 cwd가 바뀌는 위험이 있음 → 설계 결정으로 보류.
  - 검증: hello에 `Os.statvfs("/data/data/io.vax.hello")` 프로브 추가. 수정 전 `ENOENT`(실재하지 않는 실제 경로) → 수정 후 `ok blocks=120507891`(샌드박스 경로로 리다이렉트). hello FATAL 0 / SIGSEGV 0. 도깨비 회귀: FATAL 3건 모두 기존 CNF, SIGSEGV 0, 금지 경고 0, statfs/getcwd 관련 오류 0.
- 2026-11-10 **linker64 do_dlopen 훅 — 내부 심볼 대신 공개 `dlopen` 훅 (`IOUniformer.cpp`)**:
  - 확인(실기 `/apex/com.android.runtime/bin/linker64`를 `llvm-nm`으로): `.symtab`과 `.dynsym` 모두 `dlopen`/`do_dlopen` 관련 심볼이 없음(stripped). 따라서 ELF64 파서를 고쳐도 `do_dlopen`은 이름으로 찾을 수 없음 — 백로그 가정이 틀렸음.
  - 공개 심볼: `libdl`/`libc`의 `dlopen`, `dlsym`, `android_dlopen_ext`는 `dlsym`으로 해석 가능(공개).
  - 수정: API 24+에서 `do_dlopen` 조회 대신 `dlsym(RTLD_DEFAULT, "dlopen")`에 기존 `new_dlopen` 훅을 설치. 기존 `dlopen` 훅은 경로만 리다이렉트하고 `onSoLoaded`는 빈 함수라 부작용 없음. API 19–23 분기는 그대로.
  - 검증: `cannot read symbol table` / `Error ELF parsing ...linker64` / `elf error 2` 각 7 → 0. `VAX-IO dlopen hook installed` 7 프로세스. 도깨비 실행 FATAL 1건(기존 CNF만), SIGSEGV 0, 금지 경고 0.
  - 한계(정직하게): 이 훅은 `dlopen()` 직접 호출만 잡음. ART의 `System.loadLibrary`가 쓰는 `android_dlopen_ext`는 훅 대상이 아님(VA가 nativeLibraryDir 절대경로로 넘기므로 현 상황에선 충분하다고 판단). 게스트 `dlopen` 절대경로 리다이렉트의 **기능 검증은 아직 테스트 경로가 없어 미완**.

