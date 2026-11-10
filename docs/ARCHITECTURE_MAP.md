# ARCHITECTURE_MAP.md — VAX Engine Technical Map

> Generated: 2026-11-10 · Branch: `master`
> Base: `asLody/VirtualApp` (2017 core, `@version 3.5`) with Android 11–16 / API 30–36 modernization layered on top.
> Engine root: `VirtualApp/lib/src/main/java/com/lody/virtual/` + sibling reflection package `VirtualApp/lib/src/main/java/mirror/`.
> Host launcher: `VirtualApp/host/src/main/java/io/vax/host/`.

## 0. One-paragraph model

VAX runs a guest APK **inside the host app's own UID/sandbox** by (a) parsing and "installing" the guest into a private VA data dir, (b) pre-registering a fixed pool of *stub* Activities/Providers/Services in the host manifest, (c) replacing the guest process's framework service binders (ActivityManager, PackageManager, …) with dynamic proxies that rewrite package/uid/component identity on the fly, (d) brokering the "real" bookkeeping through a private `:server` process over a custom IPC bus, and (e) redirecting filesystem paths and a few sensitive native calls through a JNI engine (`libva++.so`). The guest thinks it is a normally-installed app; the framework thinks it is the host.

```
┌─ :main (host UI, io.vax.host) ──────────┐   ┌─ :server (BinderProvider) ───────────┐
│ VirtualCore.startup → inject stubs      │   │ VActivityManagerService (AMS-equiv)  │
│ VirtualCore.initialize(onMainProcess)   │◄─►│ VPackageManagerService  (PMS-equiv)  │
└─────────────────────────────────────────┘IPC│ VAppManagerService / VUserManager …  │
                                           Bus └──────────────────────────────────────┘
┌─ :p0..:p49 (guest processes, VAppClient) ────────────────────────────────┐
│ VClientImpl.bindApplicationNoCheck → LoadedApk.makeApplication(guest)     │
│  · proxied binders (am/pm/… MethodProxies rewrite identity)               │
│  · ComponentDelegate hooks (host: DexPatcher / sanitize / LiappBypass)    │
│  · NativeEngine → libva++.so  (IO redirect, /proc filter, native hooks)   │
└───────────────────────────────────────────────────────────────────────────┘
```

## 1. Process model (`client/core/VirtualCore.java`)

`VirtualCore.get()` is the singleton facade. `startup(Context)` (main thread only): builds stub CP authority → grabs `ActivityThread` via `mirror.android.app.ActivityThread` → inits `IPCBus` → `detectProcessType()` → `InvocationStubManager.init()+injectAll()` → `ContextFixer.fixContext()`.

`detectProcessType()` compares the real process name against `mainProcessName`:
| Result | Condition | Initializer callback |
|---|---|---|
| `Main` | name == mainProcessName | `onMainProcess()` |
| `Server` | ends with `Constants.SERVER_PROCESS_NAME` (`:x`) | `onServerProcess()` |
| `VAppClient` | `VActivityManager.isAppProcess(name)` | `onVirtualProcess()` |
| `CHILD` | else | `onChildProcess()` |

**Modernization anchor:** `VirtualCore.java:64-71` static block calls `HiddenApiBypass.addHiddenApiExemptions("L")` — this is what keeps the entire `mirror.*` reflection layer working against hidden framework members on API 28+. Logs use the `VAX` tag.

## 2. Hook framework (`client/hook/`)

The dynamic-proxy machinery that makes the guest see VA's bookkeeping instead of the framework's.

- `base/MethodInvocationStub.java` — wraps a base `IInterface` in a `java.lang.reflect.Proxy`; `HookInvocationHandler.invoke()` routes by method name to a registered `MethodProxy` (`beforeCall`/`call`/`afterCall`), else forwards to the real binder.
- `base/MethodInvocationProxy.java` — `IInjector` base; auto-registers inner `MethodProxy` subclasses via the `@Inject(MethodProxies.class)` annotation (skips `@SkipInject`).
- `base/BinderInvocationProxy.java` / `BinderInvocationStub.java` — ServiceManager-backed variant; `inject()` → `replaceService(name)`.
- Identity-rewrite primitives: `ReplaceCallingPkgMethodProxy`, `ReplaceLastPkgMethodProxy`, `ReplaceLastUidMethodProxy`, `ReplaceSequencePkgMethodProxy`, `ReplaceSpecPkgMethodProxy`, `ReplaceUidMethodProxy`.

**Registry:** `client/core/InvocationStubManager.java` registers different stub sets per process — Server gets only `ActivityManagerStub` + `PackageManagerStub`; `VAppClient` gets ~40 stubs gated by `Build.VERSION.SDK_INT`. **Modernized:** `addInjectorSafe(name, factory)` (InvocationStubManager.java:85-96, 198-204) — a failing hook logs under `VAX-INJECT` and is skipped instead of crashing the process (a resilience change vs. the 2017 original).

**Hooked services** (`client/hook/proxies/<group>/` = one `*Stub` + a `MethodProxies`): `am` (ActivityManager — the hot path), `pm` (PackageManager), plus account/alarm/appops/appwidget/audio/backup/bluetooth/clipboard/connectivity/content/devicepolicy/display/fingerprint/graphics/input/isms/isub/job/libcore/location/media/mount/network/notification/power/telephony/usage/user/vibrator/view(AutoFill)/wifi/window — ~50 groups total.

## 3. Guest bootstrap (`client/VClientImpl.java`)

`IVClient.Stub`, singleton `VClientImpl.get()`. **This is the guest-side application bootstrapper — there is no separate `ClientImpl.java`.**

`bindApplicationNoCheck(...)` (VClientImpl.java:220-349) is the heart of guest startup:
1. Fake `Build.SERIAL/DEVICE`; pull guest `ApplicationInfo` via `VPackageManager`.
2. Start IO redirect via `startIOUniformer()` → `NativeEngine` (when `VASettings.ENABLE_IO_REDIRECT`); `NativeEngine.launchEngine()`; `startDexOverride()`.
3. `createPackageContext(... CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY)` for the guest.
4. Fix `ActivityThread.mBoundApplication` via `mirror`; set target SDK on `VMRuntime`; patch `CompatibilityInfo`.
5. **`mInitialApplication = LoadedApk.makeApplication(...)`** → `mInstrumentation.callApplicationOnCreate(...)`.
6. Re-verify hooks (`checkEnv(AppInstrumentation.class)`, `checkEnv(HCallbackStub.class)`), install content providers, `appDoneExecuting()`.

`startIOUniformer()` (VClientImpl.java:401-437) registers the `/data/data/...`, `/data/user/0/...`, `/data/user_de/0/...`, wlan0 MAC and virtual-storage redirections.

> **VAX host integration point:** the host's `ComponentDelegate` (set in `HostApp.onVirtualProcess`) fires inside this flow — `beforeApplicationCreate` runs **before** step 5's `callApplicationOnCreate` (DexPatcher inject + path sanitize), `afterApplicationCreate` runs after (LiappBypass). This is why the host comment stresses registering the delegate in `onVirtualProcess`.

## 4. Stub components (`client/stub/`)

- `StubActivity.java` — abstract host Activity; `onCreate` reads a `StubActivityRecord` from the launch intent, re-injects `HCallbackStub`, then dispatches the real intent. **Defines pool `C0 … C99` (100 inner classes)**; `VASettings.STUB_COUNT = 50` so only the first 50 are used by the counter — the rest are headroom. *(Corrects the earlier "C0–C49" note.)*
- `StubContentProvider.java` — same 100-class `C*` pool for providers.
- `StubDialog`, `StubJob`, `StubPendingActivity/Receiver/Service`, `DaemonService` (plain sticky keep-alive; `DaemonService.java:37` notes it stays plain because Android 14+ would require a `foregroundServiceType`).

**Mapping (server-side):** `server/am/ActivityStack.java:fetchStubActivity(vpid, targetInfo)` (≈ ActivityStack.java:513-551) picks `getStubDialogName(vpid)` for floating/translucent/wallpaper themes, else `getStubActivityName(vpid)`, where **`vpid` = the virtual process index**. The stub `ComponentName` is on the host package; a `StubActivityRecord` carries the real intent/info/userId. So `C{vpid}` across `:p{n}` host processes shadows the real guest activity to satisfy the framework's manifest check.

## 5. Server process (`server/`)

`server/BinderProvider.java` — a `ContentProvider` that is the `:server` boot entry and IPC broker. `onCreate()` starts `DaemonService`, then `systemReady()` + `IPCBus.register(...)` for every service, wires `BroadcastSystem.attach(...)`, triggers `scanApps()`. `call("@")` returns the `IServiceFetcher` binder.

| Service | Implements | Role |
|---|---|---|
| `server/am/VActivityManagerService` | `IActivityManager` | AMS-equivalent (activity/service/broadcast/pendingIntent). Helpers: `ActivityStack`, `ActivityRecord`, `TaskRecord`, `ProcessRecord`, `ServiceRecord`, `BroadcastSystem`, `UidSystem`, `PendingIntents`. |
| `server/pm/VPackageManagerService` | `IPackageManager` | PMS-equivalent (resolveIntent, queryIntent*, getActivityInfo). Helpers: `IntentResolver`, `PackageCacheManager`, `PackageSetting`. |
| `server/pm/VAppManagerService` | `IAppManager` | install/scan/uninstall orchestration. |
| `server/pm/VUserManagerService` | — | multi-user. |
| + | — | `VAccountManagerService`, `VDeviceManagerService`, `VJobSchedulerService` (API≥21), `VNotificationManagerService`, `VirtualStorageService`, `VirtualLocationService`. |

**IPC transport:** `helper/ipcbus/IPCBus` + `server/ServiceCache.java` register binders; `client/ipc/ServiceManagerNative.java` does a `ProviderCall` to `BinderProvider` (method `"@"`), caches `IServiceFetcher`, resolves services by name. Client façades in `client/ipc/`: `VActivityManager`, `VPackageManager`, `VAccountManager`, `VJobScheduler`, `VNotificationManager`, `VDeviceManager`, `VirtualLocationManager`, `VirtualStorageManager`. AIDL-style Java interfaces in `server/interfaces/`.

## 6. Native engine (`client/NativeEngine.java` → `libva++.so`)

JNI bridge (`System.loadLibrary("va++")`). Native entry points: `nativeLaunchEngine`, `nativeEnableIORedirect`, `nativeIORedirect/Whitelist/Forbid`, `nativeSetGuestPackage`, `nativeGetRedirectedPath`, `nativeReverseRedirectedPath`, `nativeMark`.

- **IO redirect ("IOUniformer"):** `redirectDirectory/redirectFile/whitelist/forbid`, driven from `VClientImpl.startIOUniformer()`. `enableIORedirect()` passes `SDK_INT` + `BuildCompat.getPreviewSDKInt()`.
- **Native framework hooks:** `launchEngine()` passes three sensitive `Method` objects (`gOpenDexFileNative`, `gCameraNativeSetup`, `gAudioRecordNativeCheckPermission` from `client/natives/NativeMethods`) into native for inline-hooking. Up-callbacks: `onKillProcess`, `onGetCallingUid` (real pid → guest vuid / SYSTEM_UID), `onOpenDexFileNative` (rewrites dex output path), `onGetUid`.

**Native source** (`lib/src/main/jni/`, module `va++` via `Android.mk`, arm64-v8a): `Foundation/IOUniformer.cpp` (openat/dlopen redirect + `/proc` transparency filters), `Foundation/VMPatch.cpp` (`hookAndroidVM`), `Jni/VAJni.cpp`, `SymbolFinder`, `SandboxFs`, `Substrate`, And64InlineHook, static `fb`.

> **A16 gap (confirmed in code):** `VMPatch.cpp:hookAndroidVM` early-returns when `apiLevel >= 30` (`ALOGW("hookAndroidVM skipped on API %d")`). The native swaps of `getCallingUid`/`openDexFile`/camera/audio are therefore **disabled on Android 11+**, including the Z Flip 4's API 36. See ANDROID16_COMPATIBILITY.md §3.

## 7. Package parsing / install (`server/pm/parser/`, `server/pm/installer/`)

- `PackageParserEx.parsePackage(File)` — **Modernized (PackageParserEx.java:50-54):** on `SDK_INT >= R` delegates to `ModernPackageParser`; below that uses framework `PackageParser` via `helper/compat/PackageParserCompat`.
- `ModernPackageParser.java` (new) — reads component metadata/signatures via public `getPackageArchiveInfo`, and intent-filters/uses-library/protected-broadcast directly from the binary `AndroidManifest.xml` through an `AssetManager` (the public API omits them). This is the single most important A11+ parsing fix.
- `VPackage.java` — serializable in-engine package model used by the resolvers.
- Install: `VAppManagerService.installPackage` (VAppManagerService.java:338 flags an Android-14 "no dex from app-writable file" restriction) → `PackagePersistenceLayer`/`PackageSetting`. Session installer under `server/pm/installer/`.

## 8. OS/helpers & the mirror layer

- `os/`: `VUserHandle` (uid/user math, pervasive), `VUserManager`/`VUserInfo`, `VBinder`, `VEnvironment` (per-guest dir layout).
- `helper/compat/` — the SDK shim layer, central to modernization: `BuildCompat`, `ActivityManagerCompat`, `ApplicationThreadCompat`, `PackageParserCompat`, `StorageManagerCompat`, `SystemPropertiesCompat`, `NativeLibraryHelperCompat`, etc.
- **`lib/src/main/java/mirror/`** (sibling package) — `Ref*` reflection wrappers for hidden framework classes (`mirror.android.app.ActivityThread`, `LoadedApk`, `mirror.dalvik.system.VMRuntime`, …). This is exactly what `HiddenApiBypass` (§1) exists to keep alive on modern Android.

## 9. VAX-specific additions on top of the engine (host module)

| File | Role |
|---|---|
| `host/.../HostApp.java` | Startup; registers `ComponentDelegate`; `sanitizeDexPathList` (strips VA prefix from dexElements but **deliberately not** `nativeLibraryDir`); `sanitizeNativeLibDir`; exposes GMS/Play via `addVisibleOutsidePackage`. |
| `host/.../DexPatcher.java` | Runtime LIAPP asset XOR-decrypt + `dexElements` injection (guest-side packer support). |
| `host/.../LiappBypass.java` | Reflection neutralization of LIAPP/XShield anti-tamper fields + classloader diagnostics. |
| `lib/.../jni/Foundation/IOUniformer.cpp` (+489) | A16 `openat`/`__openat` handling + `/proc/self/{maps,cmdline,status,comm,environ,mounts,mountinfo}` sandbox-transparency filters. |
| `lib/.../proxies/pm/MethodProxies.java` | `normalizeAppInfoPaths` — strips VA prefix from dataDir/sourceDir/publicSourceDir, **keeps `nativeLibraryDir` real** (linker needs it). |
| `lib/.../proxies/am/MethodProxies.java` | `bindService` NPE guard → return 0. |

## 10. Critical bottlenecks / fragile seams (for the modernization backlog)

1. **`hookAndroidVM` disabled ≥ API 30** (VMPatch.cpp) — `getCallingUid` spoofing and native dex-open rewrite are off on the target device. Anything relying on native uid spoofing is effectively Java-only now.
2. **`nativeLibraryDir` cannot be sanitized** without breaking `System.loadLibrary`/`dlopen` — so that one path stays VA-visible; hiding is pushed to the native `dladdr`/`/proc` layer instead. Inherent tension between transparency and a working linker.
3. **Hidden-API reliance** — the whole `mirror.*` layer depends on `HiddenApiBypass`. Any future Android that tightens exemptions beyond "L" breaks the engine wholesale.
4. **ModernPackageParser** must track manifest binary-XML shape across releases; it is the chokepoint for every guest install on A11+.
5. **Stub pool is finite** (`STUB_COUNT=50`) — process/stub exhaustion is a latent scaling limit.
6. **FGS / `foregroundServiceType`** (DaemonService) and broadcast export flags (`BroadcastSystem.java:283`) are partial on A14+ — background-execution reliability is unverified on A16.
