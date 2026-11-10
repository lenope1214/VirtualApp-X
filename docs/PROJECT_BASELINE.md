# PROJECT_BASELINE.md — VAX Verified Baseline

> Generated: 2026-11-10 · Branch: `master` · This is the authoritative "ground truth" snapshot, verified against the repository (not inherited from prior summaries).

## 1. Identity

| Field | Value | Source |
|---|---|---|
| Project | **VAX** — a VirtualApp fork (app virtualization / sandbox engine) | `GOAL.md`, module namespaces |
| Base | `asLody/VirtualApp` (2017, core `@version 3.5`) | engine headers |
| Fork origin | `lenope1214/VirtualApp-X` (remote `origin`) | git remotes |
| Upstream | `asLody/VirtualApp` | git remotes |
| Host app id | `io.vax.host` | `host/build.gradle` |
| Engine namespace | `com.lody.virtual` (`:lib`) | `lib/build.gradle` |
| Goal | Modernize 2017 code to run on Android 16; run guest apps inside a virtual sandbox (clone), not as a UI overlay | `GOAL.md` |
| Final target | `com.kakaogames.dokkaebi` (도깨비의세계) | `GOAL.md` |
| First validation target | `kr.chat.anon.bindu` (빈두) | `GOAL.md` |
| Test device | Samsung Galaxy Z Flip 4 (SM-F721N), Android 16 / API 36, arm64-v8a | `GOAL.md` |

## 2. Repository structure (top level)

```
E:/virtualapp-rea/
├─ VirtualApp/                 # Gradle root (settings.gradle, build.gradle)
│  ├─ lib/                     # com.lody.virtual — the VA engine (+ mirror/ reflection, + jni/ native)
│  ├─ host/                    # io.vax.host — the VAX host launcher app  ← primary deliverable
│  ├─ hello/                   # io.vax.hello — sandbox smoke-test guest + native probe
│  └─ app/                     # io.virtualapp — legacy 2017 launcher UI (EXCLUDED from build)
├─ doc/                        # pre-existing design notes (singular)
│  ├─ dex-pipeline-map.md      # dokkaebi CNF root-cause analysis
│  ├─ prompt-sandbox-transparency.md
│  └─ VADev_eng.md             # canonical VA architecture reference
├─ docs/                       # ← THIS baseline set (plural; new, does not touch doc/)
├─ tools/run-test.sh           # build+install+launch+logfilter loop (forces JDK17)
├─ _analysis/                  # captured run logs (dok_run2.log, etc.)
└─ GOAL.md                     # Korean goal/milestone/log (authoritative intent — do not overwrite)
```

> ⚠️ **Naming collision flagged:** the repo already has `doc/` (singular) with real design notes. PROMPT 02 asked for deliverables under `docs/` (plural). These were created under `docs/` as requested, leaving `doc/` untouched. If you prefer a single docs tree, consolidate manually.

## 3. Toolchain & module config (verified)

See **BUILD_STATUS.md §1–2** for the full matrix. Headlines:
- Gradle **8.11.1**, AGP **8.10.1**, JDK **17** (shell default is JDK8 — must override), NDK **27.3.13750724**, pure **Java 8** (no Kotlin), AndroidX on.
- `:lib` (lib, com.lody.virtual, compileSdk 35), `:host` (app, io.vax.host, targetSdk 34), `:hello` (app, io.vax.hello) are built. **`:app` is excluded** (`settings.gradle` TODO(P0)): legacy `compile`/support-lib config, won't build as-is.
- arm64-v8a only.

## 4. Build & runtime status (verified)

- **Build: ✅ `BUILD SUCCESSFUL in 15s`** this session (`:host:assembleDebug :hello:assembleDebug`, with uncommitted changes applied). APKs produced; `libva++.so` ≈962 KB inside host APK.
- **Runtime (prior captures, not re-run this session):** host launches on A16; hello runs in sandbox; bindu reaches the Godot main loop then hits a native audio crash that the un-virtualized original also hits on this device/OS; dokkaebi is blocked by a `ClassNotFoundException` after its LIAPP native lib loads.
- Full detail + reproduce steps in **BUILD_STATUS.md**.

## 5. Architecture (verified)

Full map in **ARCHITECTURE_MAP.md**. One-line: `VirtualCore` splits process type (Main/Server/VAppClient/Child); `:server` (`BinderProvider`) hosts AMS/PMS-equivalents reached over a custom IPC bus; guest processes run the real guest `Application` via `VClientImpl.bindApplicationNoCheck`, with per-service dynamic-proxy hooks rewriting identity and `NativeEngine`/`libva++.so` redirecting IO and a few native calls. VAX-specific work lives in `host/` (DexPatcher, LiappBypass, path sanitize) and in the native `IOUniformer`/`VMPatch` plus `am`/`pm` `MethodProxies`.

## 6. Android 16 compatibility (verified)

Full assessment in **ANDROID16_COMPATIBILITY.md**. Headlines: hidden-API bypass + ModernPackageParser + arm64 + `/proc` transparency are confirmed working; `hookAndroidVM` native swaps are disabled ≥API 30 (by design); FGS/background on A16 is untested; native anti-tamper (dokkaebi/LIAPP) is the hard blocker.

## 7. Working tree at baseline

7 tracked files modified (+1027/−47), 1 new untracked source (`LiappBypass.java`), plus `_analysis/`, `doc/*.md`, `docs/`. All of it compiles. **None committed** — see BUILD_STATUS.md §5. Last commit `91928d3e "Add tap launcher to host MainActivity"`.

## 8. Milestones (from GOAL.md, verified against code)

- [x] P0–P3 basic clone/sandbox + hidden-API bypass (root fix).
- [x] hello app runs in sandbox.
- [x] bindu (5 split APKs + asset packs) installs & reaches Godot main loop.
- [x] Sandbox transparency v1 (commit `d4dacdd2`): `/proc` filters + path normalization + adb hiding; hello regression passes.
- [ ] IO-redirect precise verification (IOUniformer "Too many memory mapping" warning; `hookAndroidVM` API30+ skip).
- [ ] Stability / regression hardening.

## 9. Recommended development order

1. **Decide dokkaebi scope** (see MODEL_REVIEW_PACKET.md open questions) — native anti-tamper defeat is a large, separate effort; confirm it's in scope before investing.
2. **Isolate the dokkaebi `ADB_ENABLED` gate** (user retest with USB-debug off) — cheap, high-information.
3. **Verify the `hookAndroidVM`-disabled impact** — does any target path need native uid/dex swaps on A16, or is the Java proxy path sufficient?
4. **Harden IO redirect** — resolve the "Too many memory mapping" warning; it's the open P-level item.
5. **FGS/background on A16** — only if a target needs it.
6. **`:app` migration** — lowest priority; the host launcher already covers the workflow. Re-enable only if the legacy UI is wanted.

## 10. Cross-references

- Build & reproduce → `docs/BUILD_STATUS.md`
- Architecture → `docs/ARCHITECTURE_MAP.md`
- A16 compat → `docs/ANDROID16_COMPATIBILITY.md`
- External-model review packet → `docs/MODEL_REVIEW_PACKET.md`
- dokkaebi root cause → `doc/dex-pipeline-map.md`, `doc/prompt-sandbox-transparency.md`
- Intent & log → `GOAL.md`
