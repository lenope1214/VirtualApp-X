# ANDROID16_COMPATIBILITY.md — VAX on Android 16 / API 36

> Generated: 2026-11-10 · Target device: Samsung Galaxy Z Flip 4 (SM-F721N), Android 16 / API 36, arm64-v8a.
> Classification legend: **✅ Confirmed working** (executed/observed) · **🟡 Partially working** · **🔴 Broken** · **⬜ Not implemented** · **❔ Unknown — requires testing**.
> "Confirmed" here means observed in code **and** in a run captured in `_analysis/` or `GOAL.md`. Nothing below was re-executed on-device this session; device rows inherit from prior captured evidence and are marked.

## 0. Summary scoreboard

| Area | Status | Basis |
|---|---|---|
| Builds for API 36 toolchain (compileSdk 35, NDK 27) | ✅ | built this session |
| Host process launches on A16 | ✅ (prior capture) | `_analysis/dok_run2.log` |
| Hidden-API access (mirror layer) | ✅ | `HiddenApiBypass` + hello/bindu ran |
| Guest app create + launch (simple) | ✅ (prior) | hello app ran in sandbox |
| Guest app launch (Godot/Unity native) | 🟡 | bindu reaches Godot loop then audio crash (native-identical) |
| Native `hookAndroidVM` (uid/dex/camera/audio swaps) | 🔴 by design | disabled ≥ API 30 |
| `/proc` sandbox transparency | ✅ (verified) | proc-probe: 0 VA leaks in maps |
| Package parsing on A11+ | ✅ | `ModernPackageParser` path |
| FGS / background execution on A14+ | ❔ | partial code, not stress-tested |
| Anti-tamper guest (LIAPP/XShield) | 🔴 | dokkaebi CNF blocker |

## 1. Framework API changes (reflection / hidden API)

- **✅ Hidden-API exemption:** `VirtualCore.java:64-71` applies `HiddenApiBypass.addHiddenApiExemptions("L")`. This is the root fix that lets `mirror.*` reach hidden framework members on API 28–36. Without it the mirrors return null and the engine dies at startup. Confirmed working because the host starts and guests bind.
- **✅ PackageParser removal (A11):** `android.content.pm.PackageParser` is gone/reshaped on API 30+. Handled by `server/pm/parser/ModernPackageParser.java` (reads binary `AndroidManifest.xml` via `AssetManager`). `PackageParserEx.java:50-54` branches on `SDK_INT >= R`.
- **✅ PackageManager flag widening (int→long, A13):** tracked in `helper/utils/NumArg.java:5`; `am/MethodProxies.java:931` casts `BindServiceFlags` as long for API 34+ (`(int)` would throw).
- **🟡 ContentProvider param shape (A11-12):** `ProviderHook.java:149` accounts for AttributionSource/extras; present but not independently exercised on A16.

## 2. Package visibility (A11 `<queries>` / package visibility)

- **✅ Outside-package exposure:** `HostApp.onServerProcess` calls `addVisibleOutsidePackage("com.google.android.gms")` and `("com.android.vending")` so guests can resolve GMS/Play (otherwise `getPackageInfo` → NameNotFound and Google libs report "requires Play Store"). This is a deliberate A11+ visibility accommodation.
- **❔ General guest `<queries>` semantics** under A16's tightened visibility are not separately validated.

## 3. Process management & native uid spoofing

- **🔴 (by design) `hookAndroidVM` disabled ≥ API 30:** `lib/src/main/jni/Foundation/VMPatch.cpp` early-returns on `apiLevel >= 30`. The native inline-hooks for `getCallingUid`, `openDexFileNative`, camera setup and audio permission are **off** on the Z Flip 4 (API 36).
  - **Impact:** uid identity to the framework is maintained via the **Java** proxy layer (`onGetCallingUid` callback path + `ReplaceUidMethodProxy` family) rather than native swaps. Dex-output redirection that relied on `onOpenDexFileNative` is not active natively.
  - **Status of impact:** guests still run (hello, bindu), so the Java path covers the common cases. Whether any A16 guest path needs the native swap is **❔ requires testing** — this is the top open compat question.

## 4. Component lifecycle

- **✅ Activity:** stub-pool dispatch (`StubActivity` C0–C99, `STUB_COUNT=50`) + `ActivityStack.fetchStubActivity` works; hello and bindu activities launch.
- **🟡 Service / FGS:** `DaemonService.java:37` stays a plain sticky service, explicitly *not* adopting `foregroundServiceType` that Android 14+ expects for foreground promotion. Keep-alive on A16 under Doze is **❔ untested**.
- **🟡 Broadcast export flags (A14):** `server/am/BroadcastSystem.java:283-284` gates `RECEIVER_EXPORTED/NOT_EXPORTED` on `SDK_INT >= 33`. Present; not stress-tested on A16.
- **✅ bindService NPE guard:** `am/MethodProxies.java` wraps `bindService` in try/catch(NPE)→return 0 (A16 null-binder robustness).

## 5. Storage (scoped storage, A11+)

- **✅ Path redirection:** `VClientImpl.startIOUniformer()` redirects `/data/data`, `/data/user/0`, `/data/user_de/0`, virtual storage mounts. bindu loaded 5 split APKs + asset packs from redirected storage successfully.
- **❔ MediaStore / scoped-storage external access** for guests not separately validated.

## 6. Native lib compat & ABI

- **✅ ABI:** arm64-v8a only across `:lib`/`:host`/`:hello`; matches the Z Flip 4. 32-bit path not built.
- **✅ `libva++.so`:** builds with NDK 27.3.13750724, loads on device (host runs).
- **✅ A16 bionic `openat`:** `IOUniformer.cpp:~935` notes "Android 16 bionic does not export `__openat` (dlsym -> NULL)" and handles it — the +489 line diff is largely this A16 openat/proc work.
- **🟡 Linker vs. transparency tension:** `nativeLibraryDir` is intentionally **not** rewritten (would break `System.loadLibrary`/`dlopen`); hiding is done at the native `dladdr`/`/proc` layer. Confirmed-correct tradeoff (rewriting caused a documented regression).

## 7. Background execution

- **❔ Doze / App Standby / FGS restrictions on A16:** not measured. Given §4's partial FGS handling, assume **unverified**.

## 8. Security / permissions / anti-tamper

- **✅ `/proc` sandbox transparency (verified):** proc-probe from inside the guest read `/proc/self/{maps,cmdline,status,comm}` — result: **0 VA leaks** across 2590 maps lines; cmdline/comm/status Name spoofed to guest name; TracerPid 0 (`GOAL.md` 2026-11-10 log).
- **✅ `adb_enabled` hiding (Java):** Settings hook masks `adb_enabled`.
- **🔴 Native anti-tamper detection (dokkaebi/LIAPP/XShield):** this is the hard blocker.
  - Observed vector (`GOAL.md`): dokkaebi's single triggered detection = **`ADB_ENABLED`**, read **natively** by LIAPP (`libyfbyaux.so`), bypassing the Java Settings hook. Confirmed because ADB is actually on; user to retest with USB-debug off, launched by touch.
  - Downstream symptom (`_analysis/dok_run2.log`): `ClassNotFoundException: com.supercat.dokkaebi.DokkaebiUnityActivity` — the protected entry class is never installed on the classloader. Root cause per `doc/dex-pipeline-map.md`: **sandbox-detection-driven load abort**, not DEX absence.
  - `LiappBypass.java` (Java reflection neutralization of `com.xshield.da`/`com.liapp.*` flags) exists but does not defeat the **native** detection path.

## 9. Confirmed problems vs. hypotheses

**Confirmed (code + run evidence):**
- `hookAndroidVM` native swaps are off on API ≥30 (code).
- dokkaebi fails with CNF after `libyfbyaux.so` loads (log).
- dokkaebi's triggered detection vector is `ADB_ENABLED`, read natively (log).
- `/proc` transparency works (proc-probe, 0 leaks).
- Project builds; host + simple guests run.

**Hypotheses (not yet proven):**
- That `ADB_ENABLED` is the *only* gate for dokkaebi (user retest pending).
- That no A16 guest requires the disabled native uid/dex swaps (untested).
- That FGS/background works adequately on A16 (untested).
- That the bindu audio crash is purely native-OS (strongly supported: native original crashes identically — but not formally proven to be unrelated to VA).

## 10. Recommended compat test matrix (to convert ❔ → ✅/🔴)

1. dokkaebi with USB-debugging OFF, launched by on-device touch → isolate whether `ADB_ENABLED` is the sole gate.
2. A background/FGS guest (e.g. a music or sync app) → exercise `DaemonService` + FGS on A16.
3. A guest that calls `getCallingUid`-sensitive APIs → test whether the Java-only uid path suffices without native `hookAndroidVM`.
4. MediaStore-reading guest → scoped-storage path.
