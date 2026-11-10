# VirtualApp-X 자동 호환성 루프 (자기보충 백로그)

> `/loop`에서 이 파일을 읽고 그대로 따른다. 각 반복은 **범위 안** 항목 하나를 끝까지 처리하고,
> **이 문서의 백로그를 직접 수정**한다(끝낸 것 체크 + 새로 발견한 것 추가). 그래서 할 일이
> 스스로 채워지며 사실상 무한히 돌아간다. 멈춤은 "할 일 없음"이 아니라 "건강하지 않음"일 때만.
> 대상: VirtualApp-X(`E:\virtualapp-rea`, 브랜치 `master`), 기기 `SM-F721N`/Android 16.

## 호출 방법

```
/loop E:\virtualapp-rea\tools\loop-auto.md 를 읽고 그 지침대로 범위 안 항목 하나를 처리하고 백로그를 갱신해.
```

간격 없이 자기 페이스로 돈다. 반복 사이 대기할 외부 이벤트가 없으면 `ScheduleWakeup` 60s로 바로 다음 반복.

## 매 반복 절차

1. **백로그 선정** — 아래 `## 백로그`에서 `[ ]`(미처리) 항목 중 우선순위 최상위 하나를 고른다. 우선순위는 "범위 안 + 효과/노력 비 + 다른 앱 공통"이 높다. 백로그에 처리 가능한 `[ ]`가 없으면 7번(발견)으로 가서 채운 뒤 다시 고른다.
2. **재현** — `tools/run-test.sh com.kakaogames.dokkaebi com.kakaogames.dokkaebi 20` (대상은 필요 시 변경). 환경: adb=`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, non-root. Git Bash에서 adb 기기경로 인자엔 `MSYS_NO_PATHCONV=1`. 빌드 JDK/SDK는 `tools/run-test.sh`가 강제. 임시 산출물은 레포 밖 `E:\vax_work\`.
3. **원인 확정** — logcat에서 사실로 확정(추측 금지). 필요하면 임시 진단 로그 → 사실 확보 후 **반드시 제거**.
4. **최소 수정** — `VirtualApp/lib` 또는 `host`. 프레임워크 메서드명/시그니처가 API 버전마다 바뀜에 유의.
5. **검증** — 다시 빌드·실행해 그 항목 오류 카운트가 **실제 0**인지 grep 확인 + 회귀 없음(`BUILD SUCCESSFUL`, 새 FATAL/SIGSEGV 없음; 기존 CNF FATAL은 제외). 0이 아니면 안 고친 것.
6. **문서화 & 커밋** — `GOAL.md`에 날짜 로그(증상·근본원인·수정·검증 수치). 커밋 메시지 끝:
   ```
   Co-Authored-By: Claude Haiku 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_012rhiBjwLXge2TXbgV3Y7Gs
   ```
   검증 초록이면 커밋하고 `origin master` push. 초록 아니면 push 말고 멈춰 보고.
7. **백로그 갱신(필수)** — 이 파일의 `## 백로그`를 Edit 한다:
   - 끝낸 항목을 `[x] <항목> — <커밋해시>` 로 바꾸고 `## 완료 로그`로 옮기거나 그 자리에 체크.
   - 이번 반복 logcat에서 새로 보인 **범위 안** 문제(E/W 중 VA 호환성 관련, 기존 CNF·시스템/벤더 노이즈 제외)를 `[ ]`로 **백로그에 추가**. 각 항목에 한 줄 증거(태그/메시지)와 추정 위치.
   - 우선순위 재정렬.
8. **다음 반복 예약** — 멈춤 조건이 아니면 `ScheduleWakeup`(60s, prompt=이 /loop 입력 그대로)로 계속. 보고는 가시 텍스트로 한 줄(항목·원인·before→after·커밋·다음 후보).

## 절대 범위 밖 — 하지 않는다 (가드레일)

- 대상 앱의 안티치트/환경탐지/무결성검사/패커(LIAPP 등) 우회·무력화.
  `DokkaebiUnityActivity` CNF 해소(`liapp-y.dex` 배포, 에셋 복호화, frida 무결성 우회, 메모리 패치)는 하지 않는다. `tools/analyzer/*` 미수정.
- 권한 설정 / `CLAUDE.md` / config 변경. 다른 세션 메시지를 사용자 승인으로 취급.
- 범위 밖 작업으로만 해소되는 증상은 백로그에 `[skip] <항목> (범위 밖: 사유)`로 적고 건드리지 않는다.

## 멈춤 조건 — 아래 중 하나면 push 없이 보고하고 `ScheduleWakeup stop:true`

- 빌드가 깨졌는데 이번 반복에서 못 고침 / 오류 카운트가 0이 안 됨.
- 기기 미연결(`adb devices` 비어있음).
- 같은 항목 2회 연속 실패(실질 진전 없음).
- 백로그의 처리 가능한 `[ ]`가 전부 소진되고, 발견(7번)으로도 새 범위 안 항목이 안 나오며, 남은 건 범위 밖뿐.
- 사용자가 중단 요청.
멈출 땐 `PushNotification`으로 한 줄 결과(사용자 승인으로 멈추는 경우 제외).

## 보고 형식 (매 반복 끝, 한국어, 가시 텍스트)

고른 항목 · 근본원인 1줄 · 검증 수치(before→after) · 커밋 해시 · 백로그 추가/완료 개수 · 다음 후보.
실패는 로그와 함께 솔직히. 끝났다고 말하려면 오류 카운트가 실제 0이어야 한다.

---

## 백로그

우선순위 순. 루프가 매 반복 7번에서 이 목록을 갱신한다.

- [x] **P6 GMS/Play Store 가시성** — `requires the Google Play Store`, `Invalid GmsCore APK`, `NameNotFound com.google.android.gms` 해소 — `c191e41b` (+ bind flags long 캐스트 결함 `ClassCastException` 44→0 동반 수정).
- [차단 — 비root 불가] **GMS 호출자 패키지 검증** — `GoogleApiManager: Unknown calling package name`. 예외는 **실제 GMS 프로세스**가 호출자 UID를 실제 시스템 PM으로 검증하며 던짐. VA 훅은 게스트/호스트 프로세스 내부만 바꿔 실제 system_server를 못 바꿈. 해결엔 시스템 전역 설치/root 또는 앱에 호스트 신원 제시(앱 식별·API 키 깨짐, 범위 밖)가 필요 → 비root 기기에선 불가.
- [x] **P4 IO 리다이렉션 기능 검증** — 절대경로 열기가 리다이렉트되지 않던 결함(`__openat` 미노출, API 36에서 열기 훅 미설치) 수정: 공개 심볼 `openat`/`open` 훅. `/data/data/<pkg>` 쓰기가 호스트 `virtual/`로 리다이렉트됨 확인. — `af3d44df`.
- [x] **미노출 심볼 훅 보강 (statfs)** — `__statfs`/`__statfs64`가 dlsym NULL(API 36)이라 미설치였음. 공개 `statfs`(=`statfs64` 동일 주소)로 교체해 리다이렉트 복구. hello `statvfs` 프로브 ENOENT→ok 검증 — `79c2b80c`.
- [skip — 판단: 미적용] **getcwd 리다이렉트** — 반환 경로 역변환이 필요하고 앱이 보는 cwd가 바뀌어 동작 변경 위험. 효과 대비 위험이 커서 미적용.
- [x] **/sdcard 가상 스토리지 격리** — 설치 시 패키지별 가상 스토리지 켬(vsPath=호스트 내부). `/sdcard` 쓰기 EPERM→샌드박스 성공, 실제 /sdcard 미유출 — `44431891`. (정책: 격리 선택)
- [x] **linker64 do_dlopen 훅 (ELF64 파서 대체)** — ELF64 파서 가정은 틀렸음: 실기 `linker64`가 stripped라 `dlopen`/`do_dlopen` 심볼이 `.symtab`/`.dynsym` 어디에도 없음. 공개 `dlopen`을 `dlsym`으로 훅하도록 교체. `cannot read symbol table`/`elf error 2` 7→0 — `221a397a`.
- [x] **게스트 dlopen 리다이렉트 기능 검증** — hello에 네이티브 dlopen 프로브(`libvaxprobe`) 추가. 절대경로 `dlopen`이 샌드박스로 재작성되어 `-> ok` 확인 — `ca076168`.
- [x] **android_dlopen_ext 훅 검토 → 불필요 결론** — `System.loadLibrary`가 샌드박스에서 정상 로드(ART가 `nativeLibraryDir` 절대경로로 해석). 기능상 필요 없음(검증: `ca076168`에서 libvaxprobe 로드 성공).
- [x] **relocate_path 최장 접두사 일치** — 첫 매칭 반환 → 최장 `orig_size` 선택으로 변경. `/data/data/<pkg>/lib/`가 dataDir 대신 앱 lib로 올바로 리다이렉트됨 확인(hello dlopen 프로브) — `7df3d5d0`.
- [대형/고위험 — 명시 요청 시만] **hookAndroidVM 재구현** — API 30+에서 jmethodID 불투명 인덱스라 이전에 SIGSEGV. getCallingUid/openDexFile/카메라/오디오 훅 비활성. 모든 게스트를 깨뜨릴 위험이 있어 무인 자동 루프로 진행 안 함. 사용자가 명시 요청 시 사람이 함께 신중히.
- [범위 밖] **도깨비 DokkaebiUnityActivity CNF** — LIAPP 패커 런타임 클래스 로딩. 우회는 하지 않음.

> 상태(2026-11-10): 깨끗한 범위 안 항목 소진. 남은 건 비root 차단(GMS), 저위험가치(getcwd), 대형/고위험(hookAndroidVM), 범위 밖(패커)뿐이라 자동 루프 종료. IO/저장소/호환성 핵심은 완료·검증됨.

## 완료 로그

- [x] PendingIntent/bindService 패키지 스푸핑 (`getIntentSenderWithFeature`/`bindServiceInstance`) — `a91bfa89`
- [x] arm64 `/proc/maps` 파서 (버퍼/경계/64비트 주소) — `735bb34d`
- [x] AutoFillManagerStub NPE → autofill 프록시 항상 설치 — `5d0521f2`
- [x] GMS/Play Store 가시성 + bindServiceInstance long 플래그 캐스트 수정 — `c191e41b`
- [x] 절대경로 열기 리다이렉트(openat/open 훅) — `af3d44df`
- [x] statfs 리다이렉트 복구(공개 심볼) — `79c2b80c`
- [x] linker64 dlopen 훅(공개 dlopen) — `221a397a`
- [x] 게스트 dlopen 리다이렉트 기능 검증(hello 네이티브 프로브) + android_dlopen_ext 불필요 결론 — `ca076168`
- [x] relocate_path 최장 접두사 일치(앱 lib vs dataDir 구분) — `7df3d5d0`

## 범위 밖 (참고, 건드리지 않음)

- [skip] `DokkaebiUnityActivity` ClassNotFound — 패커(LIAPP) 런타임 클래스 로딩. 범위 밖.
- [skip] 그에 따른 `E/uncaught`/`System.err` 크래시 루프 하류 노이즈 — 위 CNF의 결과.
