package io.vax.host;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

/**
 * DexPatcher — 게스트 프로세스 런타임에서, 외부 배포 도구(tools/analyzer/asset_decrypt_deployer.py)가
 * 샌드박스 {@code code_cache/liapp-y.dex} 에 미리 깔아둔 패치 DEX를 앱 ClassLoader의
 * dexElements 맨 앞에 주입한다. 파일이 없으면 아무 것도 하지 않는다(모든 일반 앱에 무해한 no-op).
 *
 * 주입 원리(MultiDex)와 Android 14+ W^X 대응은 분석 레퍼런스
 * {@code tools/analyzer/DynamicDexLoader.java} 와 동일. 여기서는 "이미 배포된 파일을 읽어 주입"하는
 * 런타임 연결 경로만 담는다.
 *
 * ponytail: 네이티브 인라인 후킹(com.xshield.da / libyfbyaux.so 메서드 교체)과 기기 실검증은 미구현.
 *           DEX 섀도잉으로 막히면 jni/HookZz·And64InlineHook 경로가 다음 단계.
 */
public final class DexPatcher {

    private static final String TAG = "VAX-DexPatcher";
    private static final String PATCH_DEX = "liapp-y.dex";

    private DexPatcher() {}

    /** 배포된 패치 DEX가 있으면 주입. 없거나 실패해도 앱 부팅은 그대로 진행(throw 안 함). */
    public static void injectIfPresent(Context context) {
        if (context == null) return;
        try {
            File dexFile = new File(context.getCodeCacheDir(), PATCH_DEX);
            // 진단용: 이 로그가 보이면 게스트 프로세스에서 delegate 훅이 실제로 실행된 것.
            Log.i(TAG, "injectIfPresent: pkg=" + context.getPackageName()
                    + " dex=" + dexFile.getAbsolutePath() + " exists=" + dexFile.isFile());
            if (!dexFile.isFile() || dexFile.length() == 0) return; // no-op: 배포 안 됨

            ClassLoader target = context.getClassLoader();
            if (!(target instanceof BaseDexClassLoader)) {
                Log.w(TAG, "App ClassLoader is not BaseDexClassLoader: " + target);
                return;
            }

            // Android 14+ W^X: 쓰기 가능하면 로드 거부됨 -> 읽기 전용 보장
            ensureReadOnly(dexFile);

            File optDir = context.getDir("opt_dex", Context.MODE_PRIVATE);
            String libDir = context.getApplicationInfo().nativeLibraryDir;
            DexClassLoader patch = new DexClassLoader(
                    dexFile.getAbsolutePath(), optDir.getAbsolutePath(), libDir, target.getParent());

            if (injectFront((BaseDexClassLoader) target, patch)) {
                Log.i(TAG, "Injected patch DEX into app ClassLoader: " + dexFile.getAbsolutePath());
            } else {
                Log.w(TAG, "Patch DEX load OK but dexElements injection failed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "injectIfPresent failed (app boot continues)", t);
        }
    }

    private static void ensureReadOnly(File dexFile) {
        if (!dexFile.canWrite()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                Os.chmod(dexFile.getAbsolutePath(),
                        OsConstants.S_IRUSR | OsConstants.S_IRGRP | OsConstants.S_IROTH);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Os.chmod 0444 failed, falling back to setReadOnly()", e);
            }
        }
        dexFile.setReadOnly();
    }

    /** patch의 dexElements를 target의 dexElements 앞단에 병합 -> 겹치는 클래스는 patch가 우선 로드. */
    private static boolean injectFront(BaseDexClassLoader target, BaseDexClassLoader patch) {
        try {
            Field pathListF = field(target, "pathList");
            Object targetPL = pathListF.get(target);
            Object patchPL = pathListF.get(patch);

            Field elemsF = field(targetPL, "dexElements");
            Object targetElems = elemsF.get(targetPL);
            Object patchElems = elemsF.get(patchPL);

            int tLen = Array.getLength(targetElems);
            int pLen = Array.getLength(patchElems);
            Class<?> elemType = targetElems.getClass().getComponentType();
            Object merged = Array.newInstance(elemType, tLen + pLen);
            System.arraycopy(patchElems, 0, merged, 0, pLen);     // patch 먼저
            System.arraycopy(targetElems, 0, merged, pLen, tLen); // 기존 뒤에

            elemsF.set(targetPL, merged);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "injectFront failed", t);
            return false;
        }
    }

    private static Field field(Object obj, String name) throws NoSuchFieldException {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { /* 상위 클래스로 */ }
        }
        throw new NoSuchFieldException(name + " not found on " + obj.getClass());
    }
}
