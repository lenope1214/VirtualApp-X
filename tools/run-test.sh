#!/usr/bin/env bash
# Build host, install it, ask it to clone+launch a target app into the sandbox, print filtered logs.
# usage: tools/run-test.sh <install-pkg|-> <launch-pkg|-> [wait-seconds]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
export JAVA_HOME="E:\devtools\jdk17" ANDROID_SDK_ROOT="E:\devtools\android-sdk"
INSTALL="${1:--}"; LAUNCH="${2:--}"; WAIT="${3:-12}"

cd "$ROOT/VirtualApp" || exit 1
./gradlew.bat --no-daemon :host:assembleDebug 2>&1 | grep -E "error:|BUILD|FAILED" || exit 1
"$ADB" install -r host/build/outputs/apk/debug/host-debug.apk | tr -d '\r' | tail -1
"$ADB" logcat -c
"$ADB" shell am force-stop io.vax.host
EXTRAS=""
[ "$INSTALL" != "-" ] && EXTRAS="$EXTRAS --es install $INSTALL"
[ "$LAUNCH" != "-" ] && EXTRAS="$EXTRAS --es launch $LAUNCH"
"$ADB" shell am start -n io.vax.host/.MainActivity $EXTRAS | tail -1
sleep "$WAIT"
"$ADB" logcat -d -v brief -s VAX:V VAX-INJECT:V HELLO:V AndroidRuntime:E "VA++":V 2>&1 | tr -d '\r' | cut -c1-240 | head -${MAXLINES:-45}
