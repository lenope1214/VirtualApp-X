# MODEL_REVIEW_PACKET.md — VAX External Review Packet

> Generated: 2026-11-10 · For **manual** submission to external models (GPT, Kimi, DeepSeek, etc.).
> This is a self-contained brief. Paste the relevant section(s). **No external-model responses are included or invented** — record real answers under §7 as you collect them.

---

## 1. One-paragraph summary

VAX is a modernization fork of `asLody/VirtualApp` (2017 app-virtualization engine, core `@version 3.5`) brought up on Android 16 / API 36. It runs a guest APK **inside the host app's own UID** — parsing/installing the guest into a private dir, pre-registering stub components, replacing framework service binders with identity-rewriting dynamic proxies, brokering real bookkeeping through a private `:server` process over a custom IPC bus, and redirecting filesystem paths + a few native calls via a JNI engine (`libva++.so`). It builds (Gradle 8.11.1 / AGP 8.10.1 / JDK17 / NDK 27, arm64-v8a, pure Java). Simple guests (`hello`) and a Godot game (`bindu`) run; the final target, a LIAPP/XShield-protected Unity game (`com.kakaogames.dokkaebi`), is blocked by **native** anti-tamper that detects the sandbox and aborts class loading. Test device: Samsung Galaxy Z Flip 4 (SM-F721N), Android 16.

## 2. Architecture (condensed)

- **Process split** (`VirtualCore.detectProcessType`): Main (host UI) / Server (`BinderProvider`, AMS+PMS equivalents) / VAppClient (guest) / Child.
- **Guest bootstrap** (`VClientImpl.bindApplicationNoCheck`): fake Build fields → guest `ApplicationInfo` → start IO redirect → `LoadedApk.makeApplication` → `callApplicationOnCreate`.
- **Hooks** (`client/hook/proxies/<svc>/`): ~50 service proxies; `am`/`pm` are the hot paths; identity-rewrite primitives swap host pkg/uid ↔ guest.
- **Stubs**: `StubActivity` C0–C99 pool (`STUB_COUNT=50`); `ActivityStack.fetchStubActivity(vpid,…)` maps guest activity → `C{vpid}` on host package.
- **Native** (`libva++.so`): IOUniformer (openat/dlopen redirect + `/proc` transparency), VMPatch (`hookAndroidVM`), inline hooks on dex-open/camera/audio.
- **A11+ parsing**: `ModernPackageParser` reads binary `AndroidManifest.xml` via `AssetManager` (framework `PackageParser` is gone).
- **Modernization keystone**: `HiddenApiBypass.addHiddenApiExemptions("L")` keeps the `mirror.*` reflection layer alive on API 28+.

(Full detail: `docs/ARCHITECTURE_MAP.md`.)

## 3. Confirmed problems (code + run evidence)

1. **`hookAndroidVM` disabled ≥ API 30** — `lib/.../jni/Foundation/VMPatch.cpp` early-returns on `apiLevel >= 30`. Native swaps of `getCallingUid`/`openDexFileNative`/camera/audio are OFF on API 36. Identity now relies on the Java proxy path. *Confirmed in code.*
2. **dokkaebi blocked by native anti-tamper** — `_analysis/dok_run2.log`: `libyfbyaux.so` (LIAPP) loads, decrypts `assets/.yfbyaux.dex` (499286 B), then `FATAL … ClassNotFoundException: com.supercat.dokkaebi.DokkaebiUnityActivity`. Root cause (`doc/dex-pipeline-map.md`): sandbox-detection-driven **intentional** class-load abort, not DEX absence. *Confirmed in log.*
3. **dokkaebi detection vector = `ADB_ENABLED`, read natively** — `GOAL.md`: the single triggered LIAPP vector is `ADB_ENABLED`; the Java Settings hook doesn't catch it because LIAPP reads adb state in native code. *Confirmed in log; "sole gate?" unproven.*
4. **IO-redirect warning** — IOUniformer emits "Too many memory mapping" (open P-level item). *Observed, not root-caused.*
5. **`nativeLibraryDir` cannot be path-sanitized** — rewriting it broke `System.loadLibrary`/`dlopen` (documented regression); so that path stays VA-visible, hidden only at the native `dladdr`/`/proc` layer. *Confirmed by regression.*

## 4. Build failures / excluded modules

- **No build failures.** `:host` + `:hello` build clean (`BUILD SUCCESSFUL in 15s`).
- **`:app` is excluded** (`settings.gradle`): legacy 2017 `compile`/`com.android.support` config, `buildToolsVersion 26.0.2`, won't build on modern Gradle without an AndroidX migration. Intentional.
- **Toolchain gotcha:** shell default `JAVA_HOME` is JDK8; builds require JDK17 (`tools/run-test.sh` forces it).

## 5. Critical files (for a reviewer to request)

| File | Why it matters |
|---|---|
| `lib/.../jni/Foundation/VMPatch.cpp` | the API≥30 `hookAndroidVM` skip |
| `lib/.../jni/Foundation/IOUniformer.cpp` | A16 openat + `/proc` transparency (+489 lines) |
| `lib/.../client/VClientImpl.java` | guest bootstrap, IO-redirect wiring |
| `lib/.../client/core/VirtualCore.java` | process split + HiddenApiBypass |
| `lib/.../server/pm/parser/ModernPackageParser.java` | A11+ manifest parsing |
| `lib/.../client/hook/proxies/am/MethodProxies.java` | AMS hooks, A11–14 bind/receiver changes |
| `lib/.../client/hook/proxies/pm/MethodProxies.java` | `normalizeAppInfoPaths` (keeps nativeLibraryDir real) |
| `host/.../HostApp.java` | ComponentDelegate wiring, path sanitize, GMS visibility |
| `host/.../DexPatcher.java` | LIAPP asset decrypt + dexElements injection |
| `host/.../LiappBypass.java` | Java-side LIAPP/XShield flag neutralization |
| `doc/dex-pipeline-map.md` | dokkaebi CNF root-cause writeup |

## 6. Open questions for external models

1. **Native sandbox detection:** LIAPP (`libyfbyaux.so`) reads `ADB_ENABLED` natively, bypassing the Java Settings hook. What is the robust way to spoof `adb_enabled` / debug state at the **native** layer for an in-sandbox guest on Android 16 — intercept `__system_property_get("init.svc.adbd")` / `persist.sys.usb.*`, hook the `Settings.Global` content read in native, or filter `/proc`-exposed signals? Which signals does LIAPP actually read?
2. **`hookAndroidVM` on API ≥30:** is re-enabling native `getCallingUid`/`openDexFileNative` hooks on ART (API 30–36) feasible/worthwhile, or is the Java proxy path sufficient for all realistic guests? What breaks if it stays disabled?
3. **CNF after packer decrypt:** given the entry class is intentionally withheld after sandbox detection (not missing), is defeating detection the only viable path, or can the decrypted DEX be injected directly (and is that legally/ethically in-scope for a personal interop project)?
4. **FGS on A16:** minimal correct `foregroundServiceType` handling for `DaemonService` to keep guests alive under Doze without tripping A14+ restrictions?
5. **IOUniformer "Too many memory mapping":** likely cause on A16 bionic (mmap limit vs. redirect table growth), and mitigation?
6. **Hidden-API longevity:** risk that a future Android tightens exemptions beyond `"L"` and breaks the whole `mirror.*` layer — is there a migration path off reflection for the hottest mirrors?

## 7. External-model responses (fill in as collected — DO NOT fabricate)

| Model | Date | Question(s) addressed | Key answer / recommendation | Follow-up |
|---|---|---|---|---|
| _(e.g. GPT)_ | | | | |
| _(e.g. Kimi)_ | | | | |
| _(e.g. DeepSeek)_ | | | | |

## 8. Environment facts (for context)

- Device: Samsung Galaxy Z Flip 4 (SM-F721N), Android 16 / API 36, arm64-v8a.
- Build: Gradle 8.11.1, AGP 8.10.1, JDK 17, NDK 27.3.13750724, pure Java 8, AndroidX.
- Modules built: `:lib` (com.lody.virtual), `:host` (io.vax.host), `:hello` (io.vax.hello). `:app` excluded.
- Targets: final `com.kakaogames.dokkaebi`; validation `kr.chat.anon.bindu`.
