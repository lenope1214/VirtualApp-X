package io.vax.analyzer;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

/**
 * DynamicDexLoader
 * 
 * 안드로이드 런타임에서 외부 DEX 바이트 배열을 특정 경로(liapp-y.dex)에 기록하고,
 * DexClassLoader를 통해 com.liapp.y 클래스를 동적으로 로드 및 대체하는 모듈.
 * 
 * 핵심 고려사항:
 * 1. Android 14+ (API 34+) W^X 보안 정책: 쓰기 가능한 DEX 파일 로드 시 SecurityException 발생
 *    -> 파일 작성 완료 즉시 chmod 0444(읽기 전용) 지정 필수.
 * 2. DexPathList dexElements 배열 조작을 통한 런타임 클래스 우선순위 대체 (MultiDex 원리).
 * 3. 예외 상황 처리 및 안전한 롤백/복구.
 */
public class DynamicDexLoader {

    private static final String TAG = "DynamicDexLoader";
    private static final String TARGET_DEX_FILENAME = "liapp-y.dex";
    private static final String TARGET_CLASS_NAME = "com.liapp.y";

    /**
     * 외부 바이트 배열을 liapp-y.dex 파일로 저장하고, DexClassLoader로 로드 및 런타임 ClassLoader에 주입.
     *
     * @param context           안드로이드 컨텍스트 (앱 내부 저장소 접근용)
     * @param dexBytes          외부에서 제공된 DEX 바이너리 바이트 배열
     * @param targetClassLoader 대상 애플리케이션의 BaseDexClassLoader (보통 context.getClassLoader())
     * @return 로드된 com.liapp.y Class<?> 객체 (실패 시 null)
     */
    public static Class<?> loadAndInjectDex(Context context, byte[] dexBytes, ClassLoader targetClassLoader) {
        if (context == null || dexBytes == null || dexBytes.length == 0) {
            Log.e(TAG, "Invalid arguments for loadAndInjectDex");
            return null;
        }

        File dexFile = null;
        try {
            // 1. 격리된 내부 code_cache 디렉터리에 liapp-y.dex 파일 생성
            File codeCacheDir = context.getCodeCacheDir();
            if (!codeCacheDir.exists()) {
                codeCacheDir.mkdirs();
            }

            dexFile = new File(codeCacheDir, TARGET_DEX_FILENAME);
            if (dexFile.exists()) {
                // 기존 파일이 있을 경우 삭제 (읽기 전용 상태일 수 있으므로 먼저 권한 조정 후 삭제)
                dexFile.setWritable(true);
                dexFile.delete();
            }

            // 2. 바이트 배열 기록
            try (FileOutputStream fos = new FileOutputStream(dexFile)) {
                fos.write(dexBytes);
                fos.flush();
            }
            Log.i(TAG, "DEX file written successfully: " + dexFile.getAbsolutePath() + " (" + dexBytes.length + " bytes)");

            // 3. Android 14+ (API 34+) W^X 보호 정책 대응: 반드시 읽기 전용(0444)으로 변경해야 로드 허용됨
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    Os.chmod(dexFile.getAbsolutePath(), OsConstants.S_IRUSR | OsConstants.S_IRGRP | OsConstants.S_IROTH);
                    Log.i(TAG, "Applied chmod 0444 to " + TARGET_DEX_FILENAME + " for Android 14+ compliance");
                } catch (Exception e) {
                    Log.w(TAG, "Os.chmod failed, falling back to File.setReadOnly()", e);
                    dexFile.setReadOnly();
                }
            } else {
                dexFile.setReadOnly();
            }

            // 4. DexClassLoader 인스턴스화
            File optDir = context.getDir("opt_dex", Context.MODE_PRIVATE);
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            ClassLoader parent = targetClassLoader != null ? targetClassLoader.getParent() : context.getClassLoader();

            DexClassLoader dexClassLoader = new DexClassLoader(
                    dexFile.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    nativeLibDir,
                    parent
            );

            // 5. com.liapp.y 클래스 동적 로드 확인
            Class<?> liappClass = dexClassLoader.loadClass(TARGET_CLASS_NAME);
            Log.i(TAG, "Successfully loaded class: " + TARGET_CLASS_NAME + " from " + TARGET_DEX_FILENAME);

            // 6. 대상 ClassLoader의 dexElements 배열 맨 앞에 삽입하여 기존 클래스를 대체하도록 주입
            if (targetClassLoader instanceof BaseDexClassLoader) {
                boolean injected = injectDexElements((BaseDexClassLoader) targetClassLoader, dexClassLoader);
                if (injected) {
                    Log.i(TAG, "Successfully injected " + TARGET_DEX_FILENAME + " into target ClassLoader hierarchy!");
                } else {
                    Log.w(TAG, "Failed to inject into dexElements; class available via custom loader only");
                }
            }

            return liappClass;

        } catch (Throwable t) {
            Log.e(TAG, "Error during dynamic DEX loading & injection: " + t.getMessage(), t);
            return null;
        }
    }

    /**
     * 리플렉션을 사용하여 DexClassLoader의 dexElements를 대상 BaseDexClassLoader의 dexElements 앞단에 병합.
     * (런타임에 com.liapp.y 요청 시 새로 주입된 DEX의 클래스가 우선 로드됨)
     */
    private static boolean injectDexElements(BaseDexClassLoader targetLoader, DexClassLoader sourceLoader) {
        try {
            Field pathListField = findField(targetLoader, "pathList");
            Object targetPathList = pathListField.get(targetLoader);
            Object sourcePathList = pathListField.get(sourceLoader);

            Field dexElementsField = findField(targetPathList, "dexElements");
            Object targetElements = dexElementsField.get(targetPathList);
            Object sourceElements = dexElementsField.get(sourcePathList);

            int targetLength = Array.getLength(targetElements);
            int sourceLength = Array.getLength(sourceElements);

            Class<?> elementClass = targetElements.getClass().getComponentType();
            Object newElements = Array.newInstance(elementClass, targetLength + sourceLength);

            // 새로 주입할 sourceElements를 앞에 배치 (우선 검색 순위 확보)
            System.arraycopy(sourceElements, 0, newElements, 0, sourceLength);
            // 기존 targetElements를 뒤에 배치
            System.arraycopy(targetElements, 0, newElements, sourceLength, targetLength);

            dexElementsField.set(targetPathList, newElements);
            return true;

        } catch (Throwable t) {
            Log.e(TAG, "Failed to reflectively inject dexElements", t);
            return false;
        }
    }

    private static Field findField(Object instance, String name) throws NoSuchFieldException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                return field;
            } catch (NoSuchFieldException e) {
                // 상위 클래스로 계속 탐색
            }
        }
        throw new NoSuchFieldException("Field " + name + " not found in " + instance.getClass());
    }
}
