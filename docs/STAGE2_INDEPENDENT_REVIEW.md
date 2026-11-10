# STAGE2_INDEPENDENT_REVIEW.md — Independent Architecture & Build-System Review

> Generated: 2026-11-10 · Branch: `master` · Reviewer pass: **Stage 2 (read-only, independent)**
> Scope: Gradle/build system, Android 16 API compatibility, Java module architecture, activity-lifecycle & process management, JNI interface correctness, build reproducibility, virtualization reliability.
> **Method:** Every claim below was re-derived from source this session. Prior-session docs were read *after* forming independent observations and are treated as hypotheses to confirm or correct, not as ground truth.
> **No source files were modified. No device operations were run. No Git state was changed.**

### Security-scope exclusion (as instructed)
Third-party application protections — anti-tamper, anti-cheat, DRM, licensing, authentication — were **not analyzed**. Concretely, the following are **out of review scope** and were deliberately left unexamined beyond noting their existence and interface boundary:
- `host/.../LiappBypass.java`
- the LIAPP asset-decrypt/`dexElements`-injection portions of `host/.../DexPatcher.java`
- `IOUniformer.cpp` `dlsym_find_redirect` logic whose stated purpose is defeating a packer's native integrity/prologue checks (lines ~1111–1176)

Where these overlap legitimate virtualization (e.g. generic `/data` path redirection), only the legitimate mechanism is discussed. The dokkaebi "blocker" is a packer/DRM behavior and is therefore treated as **outside this review's remit**, not as an engineering defect to solve.

Confidence tags used below: **[VERIFIED]** = read in source this session (file:line cited); **[HYPOTHESIS]** = plausible but not proven here; **[UNKNOWN]** = cannot be determined without a run or out-of-scope analysis.

---

## 1. Executive summary

The build system and core virtualization architecture are in **materially better shape than a 2017 VirtualApp fork would suggest**, and the four baseline documents are **substantially accurate** — I found no factual contradictions in the claims I could verify, and several claims are *understated* rather than overstated. The project builds a clean, modern Gradle 8 / AGP 8.10.1 / NDK 27 toolchain targeting arm64-v8a, with a disciplined single-ABI, single-external-dependency surface.

The three load-bearing modernization seams all check out in code:
1. **Hidden-API access** via `HiddenApiBypass` in a `VirtualCore` static initializer (`VirtualCore.java:64–71`) — the keystone the entire `mirror.*` layer depends on.
2. **Android 11+ package parsing** via `ModernPackageParser` behind an `SDK_INT >= R` branch (`PackageParserEx.java:51–54`).
3. **Android 12+ service binding** — richer than the baseline docs state: three binder-method variants are proxied (`bindService`, `bindServiceInstance`, `bindIsolatedService`), which is the actual fix for the A12+ `SecurityException` on guest service binds.

The genuine engineering risks are concentrated in three areas, none of which is the (out-of-scope) DRM blocker: **(a) build reproducibility** is weak — JDK selection, SDK path, and wrapper integrity all depend on un-pinned external machine state, and `local.properties` is committed to git; **(b) the native `hookAndroidVM` swaps are inert on the target device** (API ≥30 early-return) and the project is relying entirely on the Java proxy path, whose sufficiency on A16 is unproven; **(c) compileSdk is 35 (Android 15), one release behind the API-36 target device**, so no compile-time coverage of Android 16 behavior changes exists.

**Bottom line:** the architecture is sound and the baseline docs are trustworthy. The highest-value *safe* Stage 3 work is hardening build reproducibility and converting the several `❔ untested` A16 runtime questions into evidence — not further engine surgery.

---

## 2. Verified architecture findings

### 2.1 Build system & module graph — [VERIFIED]
| Claim | Source evidence | Verdict |
|---|---|---|
| Gradle 8.11.1 | `gradle/wrapper/gradle-wrapper.properties:5` (`gradle-8.11.1-bin.zip`) | ✅ |
| AGP 8.10.1 | `build.gradle:3–4` (`version '8.10.1' apply false`) | ✅ |
| `:app` excluded; `:lib`,`:host`,`:hello` built | `settings.gradle:19–20` (TODO(P0) + `include ':lib', ':host', ':hello'`) | ✅ |
| compileSdk 35 across all 3 modules | `lib/build.gradle:7`, `host:8`, `hello:9` | ✅ |
| targetSdk: lib 28, host 34, hello 34 | `lib:13`, `host:15`, `hello:16` | ✅ |
| minSdk: lib 21, host 21, hello 24 | `lib:12`, `host:12`, `hello:16` | ✅ |
| NDK 27.3.13750724 | `lib:8`, `host:9`, `hello:10` | ✅ |
| arm64-v8a only | `lib:17`, `host:18`, `hello:18` | ✅ |
| Pure Java 8, no Kotlin | `sourceCompatibility VERSION_1_8` in all 3; no Kotlin plugin | ✅ |
| AndroidX on, Jetifier off | `gradle.properties:2–3` | ✅ |
| Single external dep: `hiddenapibypass:4.3` (pinned) | `lib/build.gradle:50` | ✅ |
| `host targetSdk 34` is deliberate (Play Protect) | `host/build.gradle:13` comment | ✅ |

**Additional observation not in baseline docs:** `gradle.properties:5` sets `android.nonTransitiveRClass=false` with the comment "VA code uses R.* and BuildConfig the old way." This is a legacy-compat choice that keeps the non-namespaced R behavior; it is correct for the current code but is a latent modernization debt (AGP defaults moved to `true`).

### 2.2 Process / core model — [VERIFIED]
`VirtualCore` is the singleton facade, and the hidden-API exemption runs in a **static initializer** (`VirtualCore.java:64–71`) wrapped in try/catch logging under tag `VAX` — so a failure degrades loudly rather than throwing at class-load. This matches `ARCHITECTURE_MAP §1` exactly and is the correct place for it (before any `mirror.*` resolves).

### 2.3 Hook-injection resilience — [VERIFIED]
`InvocationStubManager.addInjectorSafe(name, factory)` wraps each injector registration; a failure logs under `VAX-INJECT` (`InvocationStubManager.java:91`) and is skipped rather than aborting process startup. The registration list (lines 120–153+) confirms ~40+ stubs gated by `Build.VERSION.SDK_INT`. This is a real resilience improvement over stock VA and is accurately described in `ARCHITECTURE_MAP §2`.

### 2.4 Guest bootstrap — [VERIFIED]
`VClientImpl.bindApplicationNoCheck` at `VClientImpl.java:220`; IO redirect gated by `VASettings.ENABLE_IO_REDIRECT` (`:263`) → `startIOUniformer()` (`:402`); `mInitialApplication = LoadedApk.makeApplication.call(...)` at `:316`. Line references in `ARCHITECTURE_MAP §3` are accurate.

### 2.5 Stub component pool — [VERIFIED]
`StubActivity` defines inner classes `C0 … C99` (confirmed `C95`–`C99` at `StubActivity.java:330–342`); `VASettings.STUB_COUNT = 50` (`VASettings.java:18`), consumed at `VActivityManagerService.java:739` and `:819`. The baseline docs' **correction** ("C0–C99, not C0–C49; STUB_COUNT=50 uses the first 50, rest are headroom") is **confirmed correct**.

---

## 3. Corrections / enrichments to previous analysis

I found **no outright errors** in the four baseline docs. I did find places where they *under-document* the implementation, which matters because the under-documented parts are among the strongest A11+ fixes:

**3.1 Service binding is a 3-variant fix, not a one-line NPE guard. [VERIFIED — correction]**
`ARCHITECTURE_MAP §9` and `ANDROID16_COMPATIBILITY §4` reduce the `am/MethodProxies` service work to "bindService NPE guard → return 0" and "long cast for API 34." The actual implementation (`am/MethodProxies.java:918–987`) is more significant:
- `BindService` reads flags via `NumArg.toInt(args[5])` (`:933`) so both int (legacy) and long (A14 `BindServiceFlags`) widths work; NPE→`return 0` guard at `:950–952`.
- `BindServiceInstance extends BindService` (`:971–976`) overriding only the method name — this is the **Android 12+ (API 31+)** path. The comment (`:965–969`) documents the real bug: A12 routes `Context.bindService` through `IActivityManager#bindServiceInstance`, which the legacy proxy never matched, causing guest binds to hit real AMS → `SecurityException`. This is the substantive fix.
- `BindIsolatedService extends BindService` (`:982–986`) covers **Android 10–11 (API 29–30)**.

Recommendation: promote this to a first-class entry in `ARCHITECTURE_MAP §9` and `ANDROID16_COMPATIBILITY §4`, since "guest services can bind on A12+" is a core reliability property.

**3.2 `hookAndroidVM` skip is an acknowledged TODO, not a pure "by design" choice. [VERIFIED — nuance]**
`VMPatch.cpp:377–384` early-returns on `apiLevel >= 30`. The baseline docs label this "🔴 by design." The in-code comment (`:378–381`) is more precise: on A11+ `jmethodID` may be an opaque index rather than an `ArtMethod*`, so the offset-scan would dereference garbage; the swaps are *"skipped until they are re-implemented on top of real ArtMethod pointers."* So it is a **deferred/unimplemented** capability, not a permanent design decision. This changes how Stage 3 should frame it: it is a known gap with a known re-implementation path, and the open question (does any A16 guest actually need it?) determines whether that work is ever worth doing.

**3.3 The "Too many memory mapping" open item has no source anchor. [VERIFIED — caveat]**
`PROJECT_BASELINE §8` lists an open item: IOUniformer "Too many memory mapping" warning. A search of `lib/src/main/jni/` finds **no such string in source** — it is a runtime observation, not a code location. Stage 3 should treat it as a log symptom to reproduce, not a code site to patch, and should not block on it without a fresh capture.

---

## 4. Android 16 (API 36) compatibility findings

### 4.1 Confirmed-in-code A11–A16 accommodations — [VERIFIED]
- Hidden-API exemption `"L"` — `VirtualCore.java:67`.
- `ModernPackageParser` on `SDK_INT >= R` — `PackageParserEx.java:51`.
- A16 bionic `openat`: `IOUniformer.cpp:935–938` documents that A16 bionic does not export `__openat` (so the `__openat` PLT hook never installed), and adds an exported-`openat` hook (`:938–961`) to restore absolute-path redirection. This is a genuine A16-specific fix.
- Broadcast receiver export flags gated `SDK_INT >= 33` — `BroadcastSystem.java:283–285` (`RECEIVER_EXPORTED`).
- Path normalization keeps `nativeLibraryDir` real while stripping the VA prefix from `dataDir`/`sourceDir` — `pm/MethodProxies.java:73–80`, comment at `:75`. This linker-vs-transparency tradeoff is real and correctly reasoned.

### 4.2 The central A16 risk: compiling one API level behind the target — [VERIFIED — new finding]
`compileSdk 35` (Android 15) is used across all modules, but the designated test device is **API 36 (Android 16)**. Consequences:
- No compile-time access to API-36 symbols or `@FlaggedApi` behavior constants.
- Lint cannot flag Android 16 behavior changes (e.g. any further FGS, broadcast, or `/proc` tightening).
- Any A16-only framework-method signature the hook layer must match (the engine matches framework methods **reflectively by name**, so this is partially mitigated) is invisible at build time.

This is not a defect — matching by reflection is why the engine tolerates it — but it means **Android 16 behavior coverage is entirely empirical/runtime**, with zero static backstop. Raising `compileSdk` to 36 is a low-risk, high-information Stage 3 task.

### 4.3 Native uid/dex spoofing inert on the target — [VERIFIED]
Per 3.2, all native swaps (`getCallingUid`, `openDexFileNative`, camera, audio) are off on API ≥30, so the device runs **Java-proxy identity only**. Whether that is sufficient on A16 is the top open compatibility question and is **[UNKNOWN]** without targeted runtime tests.

### 4.4 Items that remain [UNKNOWN] (require a run; none attempted this session)
- FGS / `foregroundServiceType` under A16 Doze — `DaemonService.java:37,43` deliberately stays a plain `START_STICKY` service; keep-alive behavior unmeasured.
- A16 `<queries>`/package-visibility semantics for arbitrary guests (only the GMS/Play allowlisting is explicit).
- Scoped-storage / MediaStore guest access.
- Whether any guest exercises a path needing the disabled native swaps.

---

## 5. Build reproducibility assessment

**Overall: the build is deterministic *given the exact machine state*, but that state is not captured anywhere in the repo.** This is the weakest engineering area and the best target for safe Stage 3 work.

| Factor | Finding | Evidence | Severity |
|---|---|---|---|
| JDK selection not pinned at Gradle level | No `org.gradle.java.home`, no Java toolchain block anywhere. Correct JDK (17) is forced only by an env export in `tools/run-test.sh:7`, hardcoded to `E:\devtools\jdk17`. Shell default is JDK8 → build fails without the override. | grep: "(no gradle-level JDK pin)"; `run-test.sh:7` | **High** (repro) |
| `local.properties` committed to git | `git ls-files` returns `VirtualApp/local.properties`; contents `sdk.dir=E:/devtools/android-sdk`. Conventionally machine-specific and gitignored. A checkout on any other machine inherits a broken `sdk.dir`. | `git ls-files` hit; `local.properties` body | **High** (repro) |
| Gradle wrapper not integrity-pinned | No `distributionSha256Sum` in `gradle-wrapper.properties`. | grep: "(no sha256 pin)" | Medium |
| Hardcoded absolute Windows paths | `run-test.sh` and `local.properties` embed `E:\devtools\...`. Non-portable across machines/OSes. | `run-test.sh:7`, `local.properties` | Medium |
| No CI | No `.github/workflows/`. Build-green is attested only by manual local runs. | `ls .github/workflows` → absent | Medium |
| Dependency surface | **Strong point:** exactly one external dependency, version-pinned (`hiddenapibypass:4.3`); no version catalog needed, minimal drift risk. `jitpack.io` is configured as a repo (`settings.gradle:14`) but nothing observed to resolve from it. | `lib/build.gradle:50`; `settings.gradle` | Low (good) |
| Native build | `ndkBuild` via `Android.mk`, NDK pinned to a full version string (`27.3.13750724`) — reproducible as long as that NDK is installed. | module `build.gradle` | Low (good) |

The baseline `BUILD_STATUS.md` honestly flags the JDK-override requirement, which I confirm. The two **High** items (committed `local.properties`, no Gradle-level JDK pin) are the ones that would most surprise a new contributor or a fresh clone.

---

## 6. Confirmed issues and unresolved questions

### Confirmed (code evidence this session)
1. `hookAndroidVM` native swaps disabled on API ≥30 — `VMPatch.cpp:377`. **[VERIFIED]**
2. `compileSdk 35` vs API-36 target device — no compile-time A16 coverage. **[VERIFIED]**
3. `local.properties` is tracked in git with a machine-specific absolute path. **[VERIFIED]**
4. No Gradle-level JDK pin; build depends on an external env override. **[VERIFIED]**
5. `DaemonService` is a plain sticky service with no `foregroundServiceType` (A14+ FGS gap). **[VERIFIED — `DaemonService.java:37,43`]**
6. Stub pool is finite (`STUB_COUNT=50`) — latent scaling ceiling on concurrent guest processes. **[VERIFIED]**

### Unresolved questions (cannot close read-only / in-scope)
- Does any intended A16 guest require the disabled native uid/dex swaps, or does the Java proxy path suffice? **[UNKNOWN — needs run]**
- Does FGS/background keep-alive work acceptably on A16 Doze? **[UNKNOWN — needs run]**
- Is the `/proc` transparency set complete for A16 (baseline claims 0 leaks across 2590 maps lines, from a prior capture I did not re-run)? **[HYPOTHESIS — prior evidence only]**
- The dokkaebi failure is a packer/DRM behavior and is **explicitly out of this review's scope**; I neither verified nor investigated its internals.

### Reliability seams worth watching (not defects today)
- Reflective method-name matching in the hook layer is what lets `compileSdk 35` tolerate an A16 device, but it also means a renamed/split framework method on a future release fails **silently** at the proxy boundary (the bind path already shows this pattern — three names for one logical operation). A targeted A16 smoke matrix is the only way to catch such drift.
- `nativeLibraryDir` must stay VA-visible for the linker; transparency for it is pushed to the native layer. Inherent, correctly-reasoned tension (`pm/MethodProxies.java:75`).

---

## 7. Recommended safe development tasks (no behavior change, no DRM)

All of the following are read-safe or config-only and do **not** touch application-protection logic:

1. **Un-track `local.properties`** — add to `.gitignore`, `git rm --cached`. Removes the committed absolute SDK path. *(config only)*
2. **Pin the JDK at the Gradle level** — add a Java toolchain / `org.gradle.java.home` or document a `.gradle` properties override, so the build no longer depends on a hand-set env var. *(config only)*
3. **Pin the wrapper distribution** — add `distributionSha256Sum`. *(config only)*
4. **Raise `compileSdk` to 36** and rebuild — purely a compile-time coverage gain; surfaces any A16 lint/signature issues without changing runtime `targetSdk`. *(low-risk build change; verify clean build)*
5. **Add a minimal CI workflow** that runs `:host:assembleDebug :hello:assembleDebug` on a pinned JDK17 + NDK 27 image — makes "build green" reproducible and reviewable. *(additive)*
6. **Document the three bind-service variants** and the `hookAndroidVM` TODO rationale in `ARCHITECTURE_MAP` so the under-documented strengths and the deferred native work are explicit. *(docs only)*
7. **Capture a fresh `/proc` + FGS runtime log** (user-driven, on device) to convert the §6 `[HYPOTHESIS]`/`[UNKNOWN]` rows to evidence. *(runtime; operator-run, not by this review)*

---

## 8. Prioritized Stage 3 roadmap

**P0 — Reproducibility (safe, high leverage, blocks nothing else):**
- Tasks 1–3 above (un-track `local.properties`, pin JDK, pin wrapper).
- Add CI smoke build (task 5).
- *Exit criterion:* a fresh clone on a clean machine with only JDK17 + NDK 27 + SDK installed builds `:host`/`:hello` green with no manual path edits.

**P1 — Android 16 evidence (converts ❔ → fact):**
- Raise `compileSdk` to 36 (task 4); resolve any new lint.
- Operator-run A16 matrix (from `ANDROID16_COMPATIBILITY §10`): FGS/Doze guest, `getCallingUid`-sensitive guest, scoped-storage guest. Determines whether the disabled native swaps matter at all.
- *Exit criterion:* each A16 `❔` row in `ANDROID16_COMPATIBILITY §0` becomes ✅ or 🔴 with a captured log.

**P2 — Native-swap decision:**
- Using P1 evidence, decide whether to re-implement `hookAndroidVM` on real `ArtMethod` pointers (the `VMPatch.cpp:378` TODO) or to formally close it as unnecessary on A16. Do **not** invest here before P1 proves a guest needs it.

**P3 — Scaling & background hardening (only if a target needs it):**
- Revisit `STUB_COUNT`/pool exhaustion and `DaemonService` FGS adoption with `foregroundServiceType`, driven by an actual multi-process or background guest.

**Explicitly out of roadmap for this review:** anything touching anti-tamper/DRM/packer behavior (the dokkaebi path). That work is governed by a separate authorization and is outside the Stage 2 security boundary.

---

## Appendix — Inspection ledger

**Files read this session (source, verbatim):**
- Build: `settings.gradle`, `build.gradle`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, `lib/build.gradle`, `host/build.gradle`, `hello/build.gradle`, `local.properties`
- Java: `client/core/VirtualCore.java` (static block), `client/core/InvocationStubManager.java`, `server/pm/parser/PackageParserEx.java`, `client/hook/proxies/am/MethodProxies.java` (bind* region), `client/hook/proxies/pm/MethodProxies.java` (normalize region), `client/stub/StubActivity.java`, `client/stub/VASettings.java`, `client/stub/DaemonService.java`, `server/am/BroadcastSystem.java`, `client/VClientImpl.java` (bind/IO region), `server/am/VActivityManagerService.java` (stub-count usage)
- Native: `lib/src/main/jni/Foundation/VMPatch.cpp` (hookAndroidVM gate), `lib/src/main/jni/Foundation/IOUniformer.cpp` (openat/proc region — legitimate redirect only)
- Docs cross-checked: `docs/PROJECT_BASELINE.md`, `docs/ARCHITECTURE_MAP.md`, `docs/BUILD_STATUS.md`, `docs/ANDROID16_COMPATIBILITY.md`
- Repo state: `git ls-files`/`check-ignore` on `local.properties`; `.github/workflows` absence; `tools/run-test.sh` (JDK/SDK env)

**Verified:** all build-matrix values (§2.1); the three modernization keystones (hidden-API, ModernPackageParser branch, 3-variant service binding); the `hookAndroidVM` API-30 gate and its TODO rationale; the A16 `openat` fix; stub pool C0–C99 / STUB_COUNT=50; path-normalization keeping `nativeLibraryDir` real; broadcast export gating; the reproducibility gaps (committed `local.properties`, no JDK pin, no wrapper sha, no CI).

**Not verified / out of scope:** all runtime/device behavior (no runs performed); full correctness of the ~50 hook groups beyond `am`/`pm` key methods; `ModernPackageParser` internals beyond its entry branch; IPC-bus correctness end-to-end; the `/proc`-leak and dokkaebi-CNF claims (prior captures, not re-run); **all anti-tamper/DRM/packer logic (LiappBypass, DexPatcher decrypt, dlsym-prologue evasion) — excluded by the Stage 2 security boundary.**

**Files modified:** **none.** **Device operations:** **none.** **Git state changes:** **none.**
