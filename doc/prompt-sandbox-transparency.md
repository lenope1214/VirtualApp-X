# 작업 지시서: VA 샌드박스 투명성 개선 — LIAPP 탐지 해소

> 프로젝트: virtualapp-rea-wt
> 작성일: 2026-11-10
> 선행 문서: `doc/dex-pipeline-map.md`

---

## 배경

VirtualApp 기반 안드로이드 가상화 컨테이너(호스트: `io.vax.host`)에서
도깨비의세계(`com.kakaogames.dokkaebi`)를 실행하면
`DokkaebiUnityActivity`의 `ClassNotFoundException`으로 막힌다.

### 근본 원인

DEX 부재가 아니다. LIAPP 보안 모듈(`libyfbyaux.so`)이 VA 안에서 정상 로드되어
자체 복호화까지 수행하지만, **환경 무결성 검사에서 샌드박스를 탐지**하여
클래스 로딩 체인을 의도적으로 중단한다.

증거:
- `libyfbyaux.so` 로드 성공, Firebase 초기화까지 진행
- 외부 저장소에 탐지 로그 생성: `"ADB_ENABLED"`, `"For security purposes. service is not available at the tampered app"`
- 이후 `DokkaebiUnityActivity` CNF 발생

### 외부 에셋 복호화는 불필요

`tools/analyzer/asset_decrypt_deployer.py`에 의한 사전 XOR 복호화 및
`DexPatcher.java`에 의한 `liapp-y.dex` 주입은 CNF를 해소하지 못한다.
LIAPP 네이티브 엔진이 스스로 복호화를 처리하며, 차단은 복호화 이후 단계에서 일어난다.

---

## 목표

VA 샌드박스의 투명성을 개선하여, 게스트 앱이 보는 런타임 환경을
네이티브 기기 실행과 구분할 수 없게 만든다.
특정 보호 솔루션을 타겟하지 않으며, 모든 게스트 앱에 공통 적용한다.

---

## 작업 단계

### 1단계: 탐지 벡터 수집 (관찰)

LIAPP 네이티브 엔진이 실제로 어떤 시스템 정보를 읽는지 관찰한다.

```bash
# 게스트 프로세스의 시스템 콜 추적
adb shell strace -f -p <pid> -e trace=openat,read,readlinkat,stat,access 2>&1 | grep -E '/proc/|/sys/'

# LIAPP이 읽는 파일 목록 예상 후보
/proc/self/maps          # 로드된 라이브러리 목록 (VA .so 노출)
/proc/self/cmdline       # 프로세스명 (io.vax.host:p0)
/proc/self/status        # TracerPid, Uid 등
/proc/<pid>/maps         # 위와 동일 (pid 직접 지정)
/proc/<pid>/cmdline
/proc/<pid>/stat
/proc/<pid>/wchan
/sys/...                 # 기기 정보
```

수집 결과를 `doc/liapp-detection-vectors.md`에 기록한다.

### 2단계: 우선순위 결정

수집된 벡터를 아래 기준으로 분류한다.

| 우선순위 | 기준 | 예시 |
|---|---|---|
| P0 (필수) | VA 전용 정보가 그대로 노출되는 것 | `/proc/self/maps`에 VA .so, `/proc/self/cmdline`에 `io.vax.host:p0` |
| P1 (높음) | 일반적 루팅/디버깅 탐지에 걸리는 것 | `ADB_ENABLED`, `TracerPid != 0` |
| P2 (보통) | 간접 단서가 되는 것 | 패키지 경로, 서명, SELinux context |
| P3 (낮음) | 영향 불확실 | 기기 정보, 빌드 프로퍼티 |

### 3단계: 투명성 훅 구현

P0부터 순서대로 구현한다. 구현 위치는 VA의 기존 훅 인프라를 활용한다.

#### 3-A. `/proc/self/maps` 필터링

- **위치**: `IOUniformer.cpp` (네이티브 IO 훅)
- **방법**: `openat` 훅에서 `/proc/self/maps` 또는 `/proc/<게스트pid>/maps` 열기를 감지하면,
  원본 내용을 읽은 뒤 VA 관련 엔트리(`libva`, `io.vax.host`, `virtual/` 경로 등)를
  필터링한 임시 파일의 fd를 반환한다.
- **주의**: `pipe`+`write` 또는 `memfd_create`로 임시 fd 생성. 성능 영향 최소화.

#### 3-B. 프로세스명 위장

- **위치**: `IOUniformer.cpp` 또는 별도 proc 훅
- **방법**: `/proc/self/cmdline` 읽기를 훅하여 게스트 패키지명을 반환.
  `prctl(PR_SET_NAME)`으로 스레드명도 교체.
- **연관**: `/proc/self/comm`, `/proc/<pid>/stat`의 2번째 필드(comm)도 동일하게 처리.

#### 3-C. ADB 상태 은닉

- **위치**: `MethodProxies.java` 또는 `ProviderHook.java`
- **방법**: `Settings.Global.getInt("adb_enabled")`와
  `Settings.Secure.getInt("adb_enabled")` 조회를 훅하여 `0` 반환.
- **범위**: `ContentResolver` 기반 조회와 네이티브 `__system_property_get` 양쪽.

#### 3-D. 앱 경로 정규화

- **위치**: `MethodProxies.java` (PM 훅)
- **방법**: `ApplicationInfo`의 `sourceDir`, `dataDir`, `nativeLibraryDir` 등에서
  샌드박스 경로(`/data/data/io.vax.host/virtual/...`)가 아닌
  원본 패키지 경로(`/data/app/com.kakaogames.dokkaebi-xxx/...`)를 반환.
- **현재 상태**: IO 리다이렉션(`IOUniformer`)은 파일 열기를 리다이렉트하지만,
  `ApplicationInfo` 필드 자체는 샌드박스 경로를 담고 있을 수 있음. 확인 필요.

#### 3-E. 패키지 서명 반환

- **위치**: `MethodProxies.java` (PM 훅)
- **방법**: `getPackageInfo(pkg, GET_SIGNATURES)`에서 게스트 APK의 원본 서명을 반환.
- **현재 상태**: VA가 이미 처리하고 있을 수 있음. 확인 필요.

### 4단계: 검증

각 훅 구현 후 도깨비를 실행하여 아래를 확인한다.

```bash
# 탐지 로그 확인 (줄어들어야 함)
adb shell run-as io.vax.host cat \
  virtual/data/user/0/com.kakaogames.dokkaebi/app_shareds/<최신파일>

# CNF 발생 여부
adb logcat -s "AndroidRuntime" | grep ClassNotFoundException

# 회귀 확인 (기존 앱)
# 랑데부(kr.chat.anon)가 여전히 정상 실행되는지
```

---

## 구현 원칙

1. **게스트 앱 전체에 공통 적용**한다. 특정 패키지명이나 보호 솔루션명을 조건으로 분기하지 않는다.
2. **기존 훅 인프라를 활용**한다. `IOUniformer`, `MethodProxies`, `ProviderHook` 등
   이미 구축된 경로에 추가한다.
3. **관찰 → 구현 → 검증** 사이클을 반복한다.
   한 번에 모든 벡터를 막으려 하지 말고, P0부터 하나씩 적용하며 탐지 로그의 변화를 관찰한다.
4. **회귀를 확인**한다. 각 변경 후 랑데부(`kr.chat.anon`)와 hello 앱이 여전히 정상 동작하는지 검증한다.

---

## 관련 파일

| 파일 | 역할 |
|---|---|
| `VirtualApp/lib/src/main/jni/Foundation/IOUniformer.cpp` | 네이티브 IO 훅 (openat, statfs, dlopen 등) |
| `VirtualApp/lib/src/main/java/com/lody/virtual/client/hook/proxies/` | Java 프레임워크 훅 (AM, PM, Settings 등) |
| `VirtualApp/lib/src/main/java/com/lody/virtual/client/hook/providers/ProviderHook.java` | ContentProvider 훅 |
| `VirtualApp/host/src/main/java/io/vax/host/HostApp.java` | 호스트 앱 진입점, ComponentDelegate |
| `doc/dex-pipeline-map.md` | DEX 파이프라인 전체 맵 및 방안 분석 |
| `GOAL.md` | 프로젝트 목표, 마일스톤, 작업 로그 |

---

## 성공 기준

| # | 기준 | 확인 방법 |
|---|---|---|
| S1 | LIAPP 탐지 로그(`app_shareds`)에서 `tampered app` 메시지가 사라진다 | 파일 내용 확인 |
| S2 | `DokkaebiUnityActivity` CNF가 발생하지 않는다 | `logcat -s AndroidRuntime` |
| S3 | 도깨비 Unity 화면이 렌더링된다 | 스크린샷 + logcat |
| S4 | 기존 앱(랑데부, hello)에 회귀가 없다 | 실행 확인 |
