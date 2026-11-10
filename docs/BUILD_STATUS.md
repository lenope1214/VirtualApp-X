# BUILD_STATUS.md — VAX Build & Runtime Baseline

> Generated: 2026-11-10 · Session: VAX-J-1 · Branch: `master`
> Evidence-based. Every "pass" below was actually executed this session unless marked otherwise.

## 1. Toolchain (verified)

| Component | Version | Source |
|---|---|---|
| Android Gradle Plugin | **8.10.1** | `VirtualApp/build.gradle` |
| Gradle wrapper | **8.11.1** | `gradle/wrapper/gradle-wrapper.properties` |
| JDK (build) | **17** (forced) | `tools/run-test.sh` sets `JAVA_HOME=E:\devtools\jdk17` |
| Android SDK | `E:/devtools/android-sdk` | `VirtualApp/local.properties` |
| NDK | **27.3.13750724** | `lib`/`host`/`hello` `build.gradle` |
| Kotlin | **none** — pure Java, `sourceCompatibility 1.8` | all modules |
| AndroidX | enabled (`android.useAndroidX=true`, Jetifier off) | `gradle.properties` |

> ⚠️ Shell default `JAVA_HOME` is JDK8. Builds **must** export JDK17 first (or use `tools/run-test.sh`). This session built with `JAVA_HOME=E:/devtools/jdk17`.

## 2. Module matrix (verified from each `build.gradle`)

| Module | Plugin | namespace / appId | compileSdk | targetSdk | minSdk | ABI | In build? |
|---|---|---|---|---|---|---|---|
| `:lib` | `com.android.library` | `com.lody.virtual` | 35 | 28 | 21 | arm64-v8a (ndkBuild) | ✅ |
| `:host` | `com.android.application` | `io.vax.host` | 35 | **34** | 21 | arm64-v8a | ✅ |
| `:hello` | `com.android.application` | `io.vax.hello` | 35 | 34 | 24 | arm64-v8a (ndkBuild) | ✅ |
| `:app` | `com.android.application` | `io.virtualapp` | **26** | **22** | 15 | armeabi/v7a/x86 | ❌ **excluded** |

- `:app` is the legacy 2017 launcher UI. It is **commented out** of `settings.gradle` (`// TODO(P0): re-enable ':app' after migrating it to AndroidX/modern Gradle`). It still uses the old `compile` configuration, `com.android.support:*`, `buildToolsVersion '26.0.2'`, `com.flurry.android`, etc. — it will **not** build as-is.
- `host targetSdk 34` is intentional: targetSdk 28 triggered Play Protect "unsafe app blocked" on Android 16 fresh installs (`host/build.gradle` comment).
- `lib targetSdk 28` is the library's own manifest target and is not the installed app target.

## 3. Build result (EXECUTED this session)

Command:
```
cd VirtualApp
JAVA_HOME=E:/devtools/jdk17 ANDROID_SDK_ROOT=E:/devtools/android-sdk \
  ./gradlew.bat --no-daemon :host:assembleDebug :hello:assembleDebug
```
Result: **`BUILD SUCCESSFUL in 15s`** (exit 0). Native `ndkBuild` for `arm64-v8a` ran for both the engine (`libva++.so`) and the hello probe (`libvaxprobe.so`).

### Produced artifacts (on disk)
| APK | Path |
|---|---|
| Host | `VirtualApp/host/build/outputs/apk/debug/host-debug.apk` |
| Hello | `VirtualApp/hello/build/outputs/apk/debug/hello-debug.apk` |

Host APK contents (verified): `classes.dex`+`classes2-4.dex`, `lib/arm64-v8a/libva++.so` (≈962 KB).

### Gradle task status
| Task | Status |
|---|---|
| `:host:assembleDebug` | ✅ SUCCESS |
| `:hello:assembleDebug` | ✅ SUCCESS |
| `:lib` (consumed by host) | ✅ SUCCESS (incl. `ndkBuild` arm64) |
| `:app:*` | ⛔ not attempted (excluded) |

> Note: the build was run **with the current uncommitted working-tree changes applied** (see §5). It compiles clean.

## 4. On-device install / launch status

| Step | Status | Evidence |
|---|---|---|
| Host installs on device | ✅ (prior sessions) | GOAL.md log; **not re-run this session** |
| Host app launches (`io.vax.host/.MainActivity`) | ✅ (prior) | `_analysis/dok_run2.log`: `VirtualCore.startup OK`, `onMainProcess` |
| `hello` runs in sandbox | ✅ (prior) | GOAL.md P0–P3; **not re-run this session** |
| `kr.chat.anon.bindu` runs in sandbox | ⚠️ partial | reaches Godot main loop, then crashes in libgodot AudioTrack — **native original crashes identically** on this device/OS → not a VA bug |
| `com.kakaogames.dokkaebi` runs | ❌ blocked | see §6 |

> **No device action was taken this session.** The rows above marked "prior" come from `GOAL.md` and captured logs in `_analysis/`, not from a run executed now. Re-run with `tools/run-test.sh` to refresh.

## 5. Working tree (uncommitted) at baseline

`git status` (branch `master`):
```
 D  VirtualApp/app/src/main/res/raw/aider.txt
 M  VirtualApp/hello/.../HelloActivity.java            (+26)
 M  VirtualApp/host/.../io/vax/host/DexPatcher.java    (+453)
 M  VirtualApp/host/.../io/vax/host/HostApp.java       (+89)
 M  VirtualApp/lib/.../hook/proxies/am/MethodProxies.java (±8)
 M  VirtualApp/lib/.../hook/proxies/pm/MethodProxies.java (±7)
 M  VirtualApp/lib/.../jni/Foundation/IOUniformer.cpp  (+489)
 M  VirtualApp/lib/.../jni/Jni/VAJni.cpp               (+2)
 ?? VirtualApp/host/.../io/vax/host/LiappBypass.java   (new, untracked)
 ?? VirtualApp/app/src/main/res/raw/                   (untracked)
 ?? _analysis/  doc/dex-pipeline-map.md  doc/prompt-sandbox-transparency.md
```
Total tracked diff vs `HEAD`: **7 files, +1027 / −47**. All of it builds. None of it is committed — review before committing.

## 6. Current hard runtime blocker (dokkaebi) — from existing logs

From `_analysis/dok_run2.log` (captured prior session, **not re-run now**):
```
nativeloader: Load .../com.kakaogames.dokkaebi/lib/libyfbyaux.so ... : ok
VAX-DexPatcher: Found LIAPP DEX asset: assets/.yfbyaux.dex (499286 bytes)
AndroidRuntime: FATAL EXCEPTION: main
  java.lang.ClassNotFoundException: Didn't find class
  "com.supercat.dokkaebi.DokkaebiUnityActivity"
```
The LIAPP native module loads and self-decrypts, but the protected entry Activity class is never placed on the classloader (see `doc/dex-pipeline-map.md` for the root-cause analysis: sandbox-detection-driven load abort, not a DEX-absence problem). This is guest-side DRM behavior and is tracked as out-of-baseline-scope.

## 7. Reproduce the baseline build
```bash
cd E:/virtualapp-rea
tools/run-test.sh - -            # build host, install, launch host only (no guest)
# or, build only:
cd VirtualApp && JAVA_HOME=E:/devtools/jdk17 ANDROID_SDK_ROOT=E:/devtools/android-sdk \
  ./gradlew.bat --no-daemon :host:assembleDebug :hello:assembleDebug
```
