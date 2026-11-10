# DEX 파이프라인 맵 — 도깨비의세계 관련 코드 지점

> 생성일: 2026-11-10
> 최종 갱신: 2026-11-10
> 프로젝트: virtualapp-rea-wt

---

## 1. 개요

이 문서는 프로젝트 내에서 도깨비의세계(`com.kakaogames.dokkaebi`)의 DEX 파일에
직·간접적으로 관여하는 모든 코드 지점을 정리하고,
현재 막힘 지점의 근본 원인과 가능한 방안을 기록한다.

---

## 2. 핵심 발견: 외부 에셋 복호화는 불필요

### 2.1 관찰 사실 (GOAL.md 로그)

```
com.liapp.x Application 기동
  → libyfbyaux.so 네이티브 모듈 로드           ✅ 정상
  → Firebase SDK (Crashlytics, Analytics) 초기화  ✅ 정상
  → 외부 저장소에 LIAPP 무결성 탐지 로그 생성      ✅ 동작
    "ADB_ENABLED"
    "For security purposes. service is not available at the tampered app"
  → DokkaebiUnityActivity ClassNotFoundException   ❌ 여기서 끊김
```

### 2.2 결론

| 질문 | 답 | 근거 |
|---|---|---|
| LIAPP이 VA 안에서 자체 복호화를 수행하는가? | **예** | `libyfbyaux.so` 로드 성공, Firebase 초기화까지 진행 |
| `asset_decrypt_deployer.py`의 외부 사전 복호화가 필요한가? | **아니오** | LIAPP 네이티브 엔진이 이미 런타임에 복호화를 처리함 |
| CNF의 원인이 복호화 실패인가? | **아니오** | LIAPP이 **변조/샌드박스를 탐지**하여 클래스 로딩 체인을 **의도적으로 중단**한 것 |

### 2.3 실제 차단 메커니즘

```
libyfbyaux.so 로드
  → 환경 검사 (ADB, 프로세스명, 서명, 경로, /proc 등)
  → 변조 탐지 판정
  → 클래스 로딩 체인 중단 (= DokkaebiUnityActivity CNF)
  → app_shareds에 탐지 로그 기록
```

**복호화 자체는 성공하지만, 무결성 검사 결과에 따라 복호화된 클래스를
ClassLoader에 올리는 마지막 단계를 LIAPP이 건너뛴다.**

---

## 3. 런타임 코드 (빌드에 포함, 기기에서 실행)

### 3.1 DexPatcher.java

- **경로**: `VirtualApp/host/src/main/java/io/vax/host/DexPatcher.java`
- **역할**: 게스트 프로세스 부팅 시 게스트 APK의 LIAPP 암호화 에셋을 XOR 복호화하여
  `code_cache/liapp-y.dex`로 기록하고, 앱 ClassLoader의 `dexElements` 앞단에 주입.
  에셋이 없는 일반 앱에서는 no-op.
- **핵심 메서드**: `injectIfPresent(Context context)`
  1. `code_cache/liapp-y.dex` 캐시 확인 → 없으면 APK 에셋에서 복호화
  2. `assets/z/8f3d70a1` (IL2CPP metadata)의 매직(0xFAB11BAF)으로 16바이트 XOR 키 역산
  3. `assets/z/c4a19e2b` (암호화 DEX)를 동일 키로 복호화, DEX 매직(`dex\n`) 검증
  4. 복호화 DEX → `code_cache/liapp-y.dex` (Android 14+ W^X 대응: chmod 0444)
  5. 복호화 IL2CPP metadata → `files/il2cpp/Metadata/global-metadata.dat` 배포
  6. `DexClassLoader`로 로드 후 `dexElements` 앞단 병합
- **타겟 클래스**: `com.liapp.y` (LIAPP 패커가 런타임에 올리는 클래스)
- **현재 효과**: ⚠️ DEX 주입 자체는 동작하나, CNF의 근본 원인인 네이티브 무결성 탐지를 해소하지 못함 (2절 참조)
- **의존**: `asset_decrypt_deployer.py`의 기능을 **런타임으로 통합** — 외부 도구 불필요

### 3.2 HostApp.java (호출 지점)

- **경로**: `VirtualApp/host/src/main/java/io/vax/host/HostApp.java`
- **호출 위치**: 47행
- **콜백**: `ComponentDelegate.beforeApplicationCreate(Application)`
  - 게스트 `Application.onCreate()` **직전**에 실행
  - **모든 게스트 앱**에 대해 호출됨 (도깨비 전용이 아님)

```java
VirtualCore.get().setComponentDelegate(new ComponentDelegate() {
    @Override
    public void beforeApplicationCreate(Application application) {
        DexPatcher.injectIfPresent(application);  // ← 47행
    }
    // ...
});
```

---

## 4. 외부 도구 (빌드 미포함, `tools/analyzer/`)

빌드에 포함되지 않는 분석·배포 도구. PC에서 실행하거나 Frida로 주입한다.

### 4.1 asset_decrypt_deployer.py

- **경로**: `tools/analyzer/asset_decrypt_deployer.py`
- **역할**: APK 안의 LIAPP 암호화 에셋을 XOR 복호화 후 기기에 배포
- **현재 상태**: ⚠️ **불필요** — `DexPatcher.java`가 이 기능을 런타임으로 통합했으며,
  LIAPP 자체도 런타임에 복호화를 수행함. CNF의 원인은 복호화 실패가 아니라 무결성 탐지 (2절 참조)
- **처리 대상 에셋**:

| APK 내 경로 | 복호화 결과 | 배포 위치 (VA 샌드박스) |
|---|---|---|
| `assets/z/c4a19e2b` | DEX (`liapp-y.dex`) | `code_cache/liapp-y.dex` |
| `assets/z/8f3d70a1` | IL2CPP 메타데이터 (`global-metadata.dat`) | `files/il2cpp/Metadata/global-metadata.dat` |

### 4.2 DynamicDexLoader.java (레퍼런스 구현)

- **경로**: `tools/analyzer/DynamicDexLoader.java`
- **역할**: `DexPatcher.java`의 원본 설계 문서/프로토타입.
- **실제 빌드에는 포함되지 않음** — `DexPatcher.java`가 이 로직의 런타임 버전.

### 4.3 liapp_frida_agent.js

- **경로**: `tools/analyzer/liapp_frida_agent.js`
- **역할**: Frida 에이전트. 네 가지 훅 모듈:

| 모듈 | 대상 | 동작 |
|---|---|---|
| `hookIntegrityModules` | `com.liapp.LiappAgentforUnity`, `com.xshield.da` | 무결성 검증 메서드 반환값 제어 |
| `hookIntegrityModules` | `UnitySendMessage` | LIAPP 보안 탐지 통보 차단 |
| `hookDynamicClassLoading` | `DexClassLoader`, `ClassLoader.loadClass` | DEX 로드 및 클래스 조회 모니터링 |
| `hookIntentInterception` | `Activity.startActivity` 등 | `kauth.kakao.com` 인텐트 가로채기 |

### 4.4 IntentInterceptor.java

- **경로**: `tools/analyzer/IntentInterceptor.java`
- **DEX 직접 관여 아님** — 카카오 OAuth 인증 흐름 분석용.

### 4.5 proc_mem_tool.c

- **경로**: `tools/analyzer/proc_mem_tool.c`
- **DEX 직접 관여 아님** — 런타임 메모리 레벨 분석/패칭 도구.

---

## 5. 실행 흐름도

### 5.1 현재 파이프라인 (효과 없음)

```
┌─────────────────────────────────────────────────────┐
│  PC (tools/analyzer/)                               │
│                                                     │
│  asset_decrypt_deployer.py                          │
│    ├─ APK에서 암호화 에셋 추출                       │
│    ├─ 16-byte XOR 복호화                            │
│    └─ adb push ──────────────────────────┐          │
└─────────────────────────────────────────────────────┘
                                           │
                                           ▼
┌─────────────────────────────────────────────────────┐
│  기기 (VA 샌드박스)                                  │
│                                                     │
│  code_cache/liapp-y.dex  ◄── 배포됨                 │
│                                                     │
│  앱 실행 시:                                         │
│    DexPatcher.injectIfPresent()                      │
│      → liapp-y.dex → dexElements 앞단 병합           │
│      → com.liapp.y 클래스 대체됨                     │
│                                                     │
│  BUT: LIAPP 네이티브 엔진(libyfbyaux.so)이           │
│       독립적으로 무결성 검사 → 탐지 → 클래스 로딩 중단 │
│       → DokkaebiUnityActivity CNF ❌                 │
│                                                     │
│  결론: DexPatcher가 com.liapp.y를 교체해도           │
│        LIAPP 네이티브 계층의 판단은 바뀌지 않음        │
└─────────────────────────────────────────────────────┘
```

### 5.2 LIAPP 실제 동작 흐름 (VA 안에서)

```
앱 시작
  │
  ▼
com.liapp.x (Application)
  │
  ├─ System.loadLibrary("yfbyaux")
  │    → libyfbyaux.so 로드 ✅
  │
  ├─ 네이티브 환경 검사 (JNI → C/C++)
  │    ├─ ADB 연결 여부         → 탐지 ⚠️
  │    ├─ 프로세스명 검사        → io.vax.host:p0 (비정상) ⚠️
  │    ├─ /proc/self/maps 검사  → VA 관련 .so 노출 ⚠️
  │    ├─ 앱 서명/경로 검증      → 샌드박스 경로 ⚠️
  │    └─ 기타 무결성 체크       → ...
  │
  ├─ 판정: "tampered app"
  │    → app_shareds에 로그 기록
  │    → 클래스 로딩 체인 중단 ← ★ 여기가 CNF의 근본 원인
  │
  ├─ Firebase SDK 초기화 (보호 대상이 아닌 코드는 진행)
  │
  └─ DokkaebiUnityActivity 로드 시도
       → ClassNotFoundException ❌
       (LIAPP이 의도적으로 안 올림)
```

---

## 6. 관련이 있지만 DEX 직접 관여가 아닌 것

| 파일 | 관계 |
|---|---|
| `VirtualApp/lib/.../DelegateApplication64Bit.java` (137행) | VA 자체의 `dexElements` 확장 — 호스트 64비트 프로세스 부팅용. 게스트 DEX 패칭과 별개. |
| `decrypted_assets/` 디렉터리 | `asset_decrypt_deployer.py`의 출력 저장소. 복호화된 `global-metadata.dat`, `liapp-y.dex` 등이 여기 생성됨. |

---

## 7. 현재 한계

- `DexPatcher`는 `com.liapp.y` 클래스 하나만 대체 가능하며, 그것조차 CNF 해소에 효과가 없음.
- CNF의 근본 원인은 DEX 부재가 아니라 **LIAPP 네이티브 엔진의 무결성 탐지 → 의도적 로딩 중단**.
- `asset_decrypt_deployer.py`의 외부 사전 복호화는 불필요 — LIAPP이 자체 복호화를 수행함.
- `liapp_frida_agent.js`는 VirtualApp 빌드 외부 도구(Frida 필요)로 VA에 통합되어 있지 않음.

---

## 8. 방안 분석

### 8.1 근본 원인 요약

```
DokkaebiUnityActivity CNF
  └─ 원인: LIAPP이 클래스 로딩을 완료하지 않음
       └─ 원인: LIAPP 네이티브 엔진(libyfbyaux.so)이 "tampered app" 판정
            └─ 원인: VA 샌드박스 환경이 네이티브 기기와 다르게 보임
```

**핵심**: 문제의 뿌리는 DEX가 아니라 **VA 샌드박스의 투명성 부족**이다.
LIAPP이 VA 환경을 감지할 수 있는 지점이 존재하는 한, 어떤 DEX 조작도 효과가 없다.

---

## 9. 샌드박스 투명성 구현 현황 (2026-11-10)

`doc/prompt-sandbox-transparency.md`의 작업 항목 진행 상태.

### 9.1 완료된 항목

| # | 탐지 벡터 | 구현 위치 | 상태 |
|---|---|---|---|
| 3-A | `/proc/self/maps` VA .so 노출 | `IOUniformer.cpp` `open_filtered_maps()` | ✅ 커밋됨 |
| 3-B | `/proc/self/cmdline` 프로세스명 (`io.vax.host:p0`) | `IOUniformer.cpp` `open_guest_cmdline()` | ✅ 커밋됨 |
| 3-B | `/proc/self/status` TracerPid, Name | `IOUniformer.cpp` `open_filtered_status()` | ✅ 커밋됨 |
| 3-B | `/proc/self/comm` 스레드명 | `IOUniformer.cpp` `open_guest_comm()` | ✅ 구현 |
| 3-C | `adb_enabled`, `development_settings_enabled` | `SettingsProviderHook.java` | ✅ 커밋됨 |
| 3-D | `ApplicationInfo` 경로 정규화 | `MethodProxies.java` `normalizeAppInfoPaths()` | ✅ 커밋됨 |
| 3-E | 패키지 서명 반환 | VA 기본 — `PackageParserEx` 원본 서명 보존 | ✅ 기존 기능 |
| — | `V_GUEST_PKG` 환경변수 설정 | `VAJni.cpp` + `NativeEngine.java` + `VClientImpl.java` | ✅ 커밋됨 |
| — | Java 레벨 LIAPP 플래그 중화 | `LiappBypass.java` → `HostApp.afterApplicationCreate` | ✅ 구현 |

### 9.2 미확인 / 추가 검토 필요

| 벡터 | 설명 | 우선순위 | 상태 |
|---|---|---|---|
| `prctl(PR_SET_NAME)` | 커널 레벨 스레드명을 게스트 패키지로 변경 | P1 | ✅ `VAJni.cpp` `jni_nativeSetGuestPackage`에서 호출 |
| `/proc/self/mountinfo`, `/proc/self/mounts` | VA 마운트 포인트 노출 | P2 | ✅ `IOUniformer.cpp` `open_filtered_mountinfo()` |
| `/proc/self/environ` | `V_*`, `LD_PRELOAD` 환경변수 노출 | P0 | ✅ `IOUniformer.cpp` `open_filtered_environ()` |
| `getenv("LD_PRELOAD")` | 직접 `getenv` 호출로 LD_PRELOAD 확인 | P0 | ✅ `startUniformer` 끝에서 `unsetenv("LD_PRELOAD")` |
| `Process.setArgV0` | 프로세스 argv[0] 게스트명으로 설정 | — | ✅ `VirtualRuntime.setupRuntime()`이 이미 처리 |
| `/proc/self/attr/current` | SELinux context 차이 | P2 | 미구현 — 비루트 환경에서 변경 불가 |
| `__system_property_get("ro.debuggable")` | 시스템 프로퍼티 직접 접근 | P2 | 미구현 — 시스템 전역값, 위조 시 부작용 위험 |
| `nativeLibraryDir` 경로 | `normalizeAppInfoPaths`에서 의도적으로 제외 (dlopen 호환) | P3 | 의도적 유지 |
