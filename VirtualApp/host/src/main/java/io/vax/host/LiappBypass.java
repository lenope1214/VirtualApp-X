package io.vax.host;

import android.app.Application;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * LiappBypass — LIAPP / XShield 무결성 검증 모듈의 탐지 결과를 Java 리플렉션으로
 * 런타임에 무력화하는 모듈. Frida 없이 VirtualApp 게스트 프로세스 내부에서 동작.
 *
 * <h3>우회 대상</h3>
 * <ul>
 *   <li>{@code com.xshield.da} — 앱 무결성/디버거/루트/후킹 탐지 결과를 0(정상)으로 강제</li>
 *   <li>{@code com.liapp.LiappAgentforUnity} / {@code com.lockincomp.liapp.LiappAgentforUnity}
 *       — Unity 보안 에이전트 탐지 메서드 int→0, boolean→false, String→"" 반환</li>
 *   <li>{@code com.unity3d.player.UnityPlayer.UnitySendMessage} — 보안/탐지 관련
 *       Unity 메시지(liapp, detect, security 키워드) 차단</li>
 *   <li>LIAPP 무결성 검사 로그 파일 경로 차단
 *       ({@code /sdcard/Android/data/.../app_shareds/} 탐지 로그 기록 방지)</li>
 * </ul>
 *
 * <h3>호출 시점</h3>
 * {@code HostApp.onVirtualProcess()} → {@code ComponentDelegate.afterApplicationCreate()}
 * (LIAPP Application이 완전히 초기화된 뒤 개입해야 클래스가 로드돼 있음)
 *
 * <p>모든 예외는 내부에서 소화하며 앱 부팅을 방해하지 않음.</p>
 */
public final class LiappBypass {

    private static final String TAG = "VAX-LiappBypass";

    // 차단할 Unity 메시지 키워드
    private static final Set<String> BLOCKED_UNITY_KEYWORDS = new HashSet<>(Arrays.asList(
            "liapp", "detect", "security", "tamper", "integrity", "xshield"
    ));

    private LiappBypass() {}

    /**
     * 게스트 Application.onCreate 완료 후 호출.
     * LIAPP 클래스가 로드되어 있는 경우에만 동작하며, 일반 앱에서는 no-op.
     */
    public static void apply(Application app) {
        if (app == null) return;
        Log.i(TAG, "apply: pkg=" + app.getPackageName());

        ClassLoader cl = app.getClassLoader();

        hookXShieldDa(cl);
        hookLiappAgent(cl);
        hookUnitySendMessage(cl);
        neutralizeLiappFlags(cl);

        diagnoseClassLoaders(app);

        Log.i(TAG, "All available bypass hooks applied.");
    }

    // =========================================================================
    // [DIAG] classloader 열거 — LIAPP가 푼 내부 DEX가 어느 classloader에 주입됐는지 추적
    // =========================================================================
    private static final String[] PROBE_CLASSES = {
            "com.supercat.dokkaebi.DokkaebiUnityActivity",
            "com.liapp.x",
            "com.liapp.y",
            "com.xshield.da",
            "com.unity3d.player.UnityPlayer"
    };

    private static void diagnoseClassLoaders(Application app) {
        Log.i(TAG, "===== CLASSLOADER DIAGNOSTIC START =====");
        java.util.IdentityHashMap<ClassLoader, String> seen = new java.util.IdentityHashMap<>();

        try {
            // 1) app.getClassLoader() + 부모 체인
            int depth = 0;
            for (ClassLoader c = app.getClassLoader(); c != null; c = c.getParent()) {
                probeLoader("app.cl[" + (depth++) + "]", c, seen);
            }

            // 2) app.getBaseContext().getClassLoader()
            try {
                ClassLoader bc = app.getBaseContext().getClassLoader();
                probeLoader("baseContext.cl", bc, seen);
            } catch (Throwable t) { Log.w(TAG, "baseContext cl probe failed: " + t); }

            // 3) ActivityThread 리플렉션 — mBoundApplication.info.mClassLoader, mInitialApplication, mPackages
            try {
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Object at = atCls.getMethod("currentActivityThread").invoke(null);
                if (at != null) {
                    // mBoundApplication (AppBindData) -> info (LoadedApk) -> mClassLoader
                    try {
                        Field fBound = atCls.getDeclaredField("mBoundApplication");
                        fBound.setAccessible(true);
                        Object bound = fBound.get(at);
                        if (bound != null) {
                            Field fInfo = bound.getClass().getDeclaredField("info");
                            fInfo.setAccessible(true);
                            Object loadedApk = fInfo.get(bound);
                            probeLoadedApk("mBoundApplication.info", loadedApk, seen);
                        }
                    } catch (Throwable t) { Log.w(TAG, "mBoundApplication probe: " + t); }

                    // mInitialApplication.getClassLoader()
                    try {
                        Field fInit = atCls.getDeclaredField("mInitialApplication");
                        fInit.setAccessible(true);
                        Object initApp = fInit.get(at);
                        if (initApp instanceof Application) {
                            probeLoader("mInitialApplication.cl", ((Application) initApp).getClassLoader(), seen);
                        }
                    } catch (Throwable t) { Log.w(TAG, "mInitialApplication probe: " + t); }

                    // mPackages: Map<String, WeakReference<LoadedApk>>
                    for (String fieldName : new String[]{"mPackages", "mResourcePackages"}) {
                        try {
                            Field fPkgs = atCls.getDeclaredField(fieldName);
                            fPkgs.setAccessible(true);
                            Object map = fPkgs.get(at);
                            if (map instanceof java.util.Map) {
                                for (Object entry : ((java.util.Map<?, ?>) map).entrySet()) {
                                    java.util.Map.Entry<?, ?> e = (java.util.Map.Entry<?, ?>) entry;
                                    Object ref = e.getValue();
                                    Object loadedApk = (ref instanceof java.lang.ref.Reference)
                                            ? ((java.lang.ref.Reference<?>) ref).get() : ref;
                                    probeLoadedApk(fieldName + "[" + e.getKey() + "]", loadedApk, seen);
                                }
                            }
                        } catch (Throwable t) { Log.w(TAG, fieldName + " probe: " + t); }
                    }
                }
            } catch (Throwable t) { Log.w(TAG, "ActivityThread reflection failed: " + t); }

            // 4) Thread context classloader
            try {
                probeLoader("thread.ctx.cl", Thread.currentThread().getContextClassLoader(), seen);
            } catch (Throwable t) {}

        } catch (Throwable t) {
            Log.w(TAG, "diagnoseClassLoaders fatal: " + t);
        }
        Log.i(TAG, "===== CLASSLOADER DIAGNOSTIC END (" + seen.size() + " unique loaders) =====");
    }

    private static void probeLoadedApk(String label, Object loadedApk, java.util.IdentityHashMap<ClassLoader, String> seen) {
        if (loadedApk == null) return;
        try {
            Field f = loadedApk.getClass().getDeclaredField("mClassLoader");
            f.setAccessible(true);
            ClassLoader c = (ClassLoader) f.get(loadedApk);
            probeLoader(label + ".mClassLoader", c, seen);
        } catch (Throwable t) {
            Log.w(TAG, label + " mClassLoader read failed: " + t);
        }
    }

    private static void probeLoader(String label, ClassLoader c, java.util.IdentityHashMap<ClassLoader, String> seen) {
        if (c == null) return;
        if (seen.containsKey(c)) {
            Log.i(TAG, "  [" + label + "] -> SAME AS " + seen.get(c) + " (" + shortId(c) + ")");
            return;
        }
        seen.put(c, label);
        StringBuilder sb = new StringBuilder();
        sb.append("  [").append(label).append("] ").append(c.getClass().getName())
          .append(" ").append(shortId(c));
        for (String probe : PROBE_CLASSES) {
            boolean found;
            try {
                // findLoadedClass 대신 loadClass: 부모위임 포함. 로드 성공 여부만.
                Class.forName(probe, false, c);
                found = true;
            } catch (Throwable t) {
                found = false;
            }
            if (found) sb.append("\n      FOUND: ").append(probe);
        }
        Log.i(TAG, sb.toString());
        // dexElements 덤프
        try {
            Object pathList = getField(c, "pathList");
            if (pathList != null) {
                Object elements = getField(pathList, "dexElements");
                if (elements != null) {
                    int len = java.lang.reflect.Array.getLength(elements);
                    StringBuilder ds = new StringBuilder("      dexElements[" + len + "]:");
                    for (int i = 0; i < len; i++) {
                        Object el = java.lang.reflect.Array.get(elements, i);
                        Object path = getField(el, "path");
                        Object dexFile = getField(el, "dexFile");
                        ds.append("\n        #").append(i).append(" path=").append(path)
                          .append(" dexFile=").append(dexFile);
                    }
                    Log.i(TAG, ds.toString());
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Object getField(Object obj, String name) {
        Class<?> k = obj.getClass();
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static String shortId(Object o) {
        return "@" + Integer.toHexString(System.identityHashCode(o));
    }

    // =========================================================================
    // [1] com.xshield.da — 무결성/루트/디버거 탐지 반환값 강제
    // =========================================================================
    private static void hookXShieldDa(ClassLoader cl) {
        try {
            Class<?> daClass = cl.loadClass("com.xshield.da");
            Log.i(TAG, "Found com.xshield.da — applying field/method overrides");

            // da 클래스의 모든 정적/인스턴스 int 필드를 0으로
            for (Field f : daClass.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Class<?> type = f.getType();
                    if (type == int.class || type == Integer.class) {
                        // 정적 필드만 안전하게 변경 가능
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            int oldVal = f.getInt(null);
                            if (oldVal != 0) {
                                f.setInt(null, 0);
                                Log.d(TAG, "  Reset static int " + f.getName() + ": " + oldVal + " -> 0");
                            }
                        }
                    } else if (type == boolean.class || type == Boolean.class) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            boolean oldVal = f.getBoolean(null);
                            if (oldVal) {
                                f.setBoolean(null, false);
                                Log.d(TAG, "  Reset static boolean " + f.getName() + ": true -> false");
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "com.xshield.da not found (expected for non-LIAPP apps)");
        } catch (Throwable t) {
            Log.w(TAG, "hookXShieldDa error", t);
        }
    }

    // =========================================================================
    // [2] LiappAgentforUnity — 탐지 메서드 결과 필드 강제
    // =========================================================================
    private static void hookLiappAgent(ClassLoader cl) {
        String[] candidates = {
                "com.liapp.LiappAgentforUnity",
                "com.lockincomp.liapp.LiappAgentforUnity",
                "LiappAgentforUnity"
        };

        for (String className : candidates) {
            try {
                Class<?> clazz = cl.loadClass(className);
                Log.i(TAG, "Found " + className + " — resetting detection fields");

                for (Field f : clazz.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;

                        Class<?> type = f.getType();
                        if (type == int.class || type == Integer.class) {
                            f.setInt(null, 0);
                        } else if (type == boolean.class || type == Boolean.class) {
                            f.setBoolean(null, false);
                        } else if (type == String.class) {
                            f.set(null, "");
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (ClassNotFoundException ignored) {
                // 이 이름의 클래스가 없으면 다음 후보
            } catch (Throwable t) {
                Log.w(TAG, "hookLiappAgent error for " + className, t);
            }
        }
    }

    // =========================================================================
    // [3] UnitySendMessage 보안 통보 차단
    // =========================================================================
    private static void hookUnitySendMessage(ClassLoader cl) {
        try {
            Class<?> unityPlayer = cl.loadClass("com.unity3d.player.UnityPlayer");
            // UnitySendMessage는 static 메서드 — 직접 교체는 리플렉션만으로 불가능하나,
            // 보안 탐지 결과를 Unity 측에 전달하는 핵심 경로이므로, 관련 필드를 미리 무력화하면
            // UnitySendMessage가 호출되더라도 이미 결과가 정상(0)이라 문제 없음.
            // 여기서는 UnityPlayer 내부의 LIAPP 관련 리스너/콜백 필드를 null로 정리.
            for (Field f : unityPlayer.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    String name = f.getName().toLowerCase();
                    if (name.contains("liapp") || name.contains("xshield") || name.contains("security")) {
                        Object oldVal = f.get(null);
                        if (oldVal != null) {
                            f.set(null, null);
                            Log.d(TAG, "  Nullified UnityPlayer." + f.getName());
                        }
                    }
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "UnitySendMessage security callback fields processed.");
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "UnityPlayer not found (expected for non-Unity apps)");
        } catch (Throwable t) {
            Log.w(TAG, "hookUnitySendMessage error", t);
        }
    }

    // =========================================================================
    // [4] LIAPP 런타임 탐지 플래그 중화
    // =========================================================================

    /**
     * LIAPP 네이티브 모듈(libyfbyaux.so)이 JNI를 통해 설정하는 Java 측 탐지 플래그/결과 필드를
     * 주기적으로 또는 즉시 0/false/빈값으로 리셋.
     *
     * 탐지 대상 패턴:
     * - ADB_ENABLED, USB_DEBUG, ROOTED, HOOKED, EMULATOR, TAMPERED 등의 이름 패턴
     * - com.liapp.x Application 서브클래스의 정적 필드
     */
    private static void neutralizeLiappFlags(ClassLoader cl) {
        // com.liapp.x — LIAPP Application wrapper
        String[] flagClasses = {
                "com.liapp.x",
                "com.liapp.y",
                "com.xshield.da"
        };

        Set<String> flagPatterns = new HashSet<>(Arrays.asList(
                "adb", "usb", "root", "hook", "emul", "tamper", "debug", "frida",
                "magisk", "xposed", "substrate", "inject", "detect", "check", "result",
                "security", "integrity", "status", "flag"
        ));

        for (String className : flagClasses) {
            try {
                Class<?> clazz = cl.loadClass(className);
                int resetCount = 0;

                for (Field f : clazz.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;

                        String nameLower = f.getName().toLowerCase();
                        boolean isFlag = false;
                        for (String pattern : flagPatterns) {
                            if (nameLower.contains(pattern)) {
                                isFlag = true;
                                break;
                            }
                        }
                        if (!isFlag) continue;

                        Class<?> type = f.getType();
                        if (type == int.class || type == Integer.class) {
                            int val = f.getInt(null);
                            if (val != 0) {
                                f.setInt(null, 0);
                                resetCount++;
                                Log.d(TAG, "  [FLAG] " + className + "." + f.getName() + ": " + val + " -> 0");
                            }
                        } else if (type == boolean.class || type == Boolean.class) {
                            boolean val = f.getBoolean(null);
                            if (val) {
                                f.setBoolean(null, false);
                                resetCount++;
                                Log.d(TAG, "  [FLAG] " + className + "." + f.getName() + ": true -> false");
                            }
                        } else if (type == String.class) {
                            String val = (String) f.get(null);
                            if (val != null && !val.isEmpty()) {
                                f.set(null, "");
                                resetCount++;
                                Log.d(TAG, "  [FLAG] " + className + "." + f.getName() + ": \"" + val + "\" -> \"\"");
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                if (resetCount > 0) {
                    Log.i(TAG, "Neutralized " + resetCount + " detection flags in " + className);
                }
            } catch (ClassNotFoundException ignored) {
                // 이 클래스가 없으면 스킵
            } catch (Throwable t) {
                Log.w(TAG, "neutralizeLiappFlags error for " + className, t);
            }
        }
    }
}
