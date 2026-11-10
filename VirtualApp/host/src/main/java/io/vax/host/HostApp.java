package io.vax.host;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.delegate.ComponentDelegate;
import com.lody.virtual.client.stub.VASettings;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.List;

public class HostApp extends Application {
    static final String TAG = "VAX";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        VASettings.ENABLE_IO_REDIRECT = true;
        VASettings.ENABLE_INNER_SHORTCUT = false;
        try {
            VirtualCore.get().startup(base);
            Log.i(TAG, "VirtualCore.startup OK");
        } catch (Throwable e) {
            Log.e(TAG, "VirtualCore.startup FAILED", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            VirtualCore.get().initialize(new VirtualCore.VirtualInitializer() {
                @Override
                public void onMainProcess() {
                    Log.i(TAG, "onMainProcess");
                }

                @Override
                public void onVirtualProcess() {
                    Log.i(TAG, "onVirtualProcess");
                    // 게스트 프로세스 전용 콜백(VClientImpl.bindApplicationNoCheck보다 먼저 실행).
                    // 여기서 등록해야 beforeApplicationCreate(게스트 Application.onCreate 직전)가 걸림.
                    VirtualCore.get().setComponentDelegate(new ComponentDelegate() {
                        @Override
                        public void beforeApplicationCreate(Application application) {
                            DexPatcher.injectIfPresent(application);
                            sanitizeNativeLibDir(application);
                            sanitizeDexPathList(application);
                        }

                        @Override
                        public void afterApplicationCreate(Application application) {
                            LiappBypass.apply(application);
                        }

                        @Override
                        public void beforeActivityCreate(Activity activity) {
                        }

                        @Override
                        public void beforeActivityResume(Activity activity) {
                        }

                        @Override
                        public void beforeActivityPause(Activity activity) {
                        }

                        @Override
                        public void beforeActivityDestroy(Activity activity) {
                        }

                        @Override
                        public void afterActivityCreate(Activity activity) {
                        }

                        @Override
                        public void afterActivityResume(Activity activity) {
                        }

                        @Override
                        public void afterActivityPause(Activity activity) {
                        }

                        @Override
                        public void afterActivityDestroy(Activity activity) {
                        }

                        @Override
                        public void onSendBroadcast(Intent intent) {
                        }
                    });
                }

                @Override
                public void onServerProcess() {
                    Log.i(TAG, "onServerProcess");
                    // Let guests discover the real Google Play services and Play Store. Without this
                    // they fall outside isVisiblePackage(), so getPackageInfo("com.google.android.gms")
                    // and ("com.android.vending") return NameNotFound and Google libs report
                    // "requires the Google Play Store" / "Invalid GmsCore APK". This only exposes
                    // installed packages for lookup; it does not alter what those services verify.
                    VirtualCore.get().addVisibleOutsidePackage("com.google.android.gms");
                    VirtualCore.get().addVisibleOutsidePackage("com.android.vending");
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "VirtualCore.initialize FAILED", e);
        }
    }

    private static String stripVa(String path, String vaPrefix) {
        if (path != null && path.contains(vaPrefix)) {
            return path.replace(vaPrefix, "");
        }
        return path;
    }

    @SuppressWarnings("unchecked")
    private static void sanitizeDexPathList(Application app) {
        try {
            String hostData = VirtualCore.get().getContext().getApplicationInfo().dataDir;
            String vaPrefix = hostData + "/virtual";

            ClassLoader cl = app.getClassLoader();
            Field pathListField = cl.getClass().getSuperclass().getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(cl);
            if (pathList == null) return;

            // NOTE: We intentionally do NOT rewrite nativeLibraryDirectories or
            // nativeLibraryPathElements here. The dynamic linker resolves native
            // libraries through these classloader fields (and the linker namespace),
            // which do NOT pass through the openat() IO redirect. Rewriting them to a
            // fake /data/app/<pkg>/lib path leaves no real file behind the path, so
            // dlopen() fails with UnsatisfiedLinkError — breaking EVERY guest's native
            // lib loading (System.loadLibrary), including Unity .so's and the packer's
            // own native engine. Detection-visible path hiding is done separately via
            // the guest-visible copies (ApplicationInfo) and the native /proc filters;
            // the linker must keep using the real VA path. See VAX regression report.

            // Patch dexElements (Element[]) — each has a File path/zip
            try {
                Field dexElField = pathList.getClass().getDeclaredField("dexElements");
                dexElField.setAccessible(true);
                Object elements = dexElField.get(pathList);
                if (elements != null) {
                    int len = Array.getLength(elements);
                    for (int i = 0; i < len; i++) {
                        Object el = Array.get(elements, i);
                        for (String fname : new String[]{"path", "zip", "dir"}) {
                            try {
                                Field f = el.getClass().getDeclaredField(fname);
                                f.setAccessible(true);
                                Object val = f.get(el);
                                if (val instanceof File) {
                                    String orig = ((File) val).getPath();
                                    String cleaned = stripVa(orig, vaPrefix);
                                    if (!cleaned.equals(orig)) {
                                        Log.d(TAG, "sanitizeDexPathList dexEl." + fname + ": " + orig + " -> " + cleaned);
                                        f.set(el, new File(cleaned));
                                    }
                                }
                            } catch (NoSuchFieldException ignored2) {}
                        }
                    }
                }
            } catch (NoSuchFieldException ignored) {}

            Log.d(TAG, "sanitizeDexPathList complete");
        } catch (Throwable e) {
            Log.w(TAG, "sanitizeDexPathList failed", e);
        }
    }

    private static void sanitizeNativeLibDir(Application app) {
        try {
            ApplicationInfo ai = app.getApplicationInfo();
            if (ai == null || ai.nativeLibraryDir == null) return;
            String hostData = VirtualCore.get().getContext().getApplicationInfo().dataDir;
            String vaPrefix = hostData + "/virtual";
            if (ai.nativeLibraryDir.startsWith(vaPrefix)) {
                String cleaned = ai.nativeLibraryDir.substring(vaPrefix.length());
                Log.d(TAG, "sanitizeNativeLibDir: " + ai.nativeLibraryDir + " -> " + cleaned);
                ai.nativeLibraryDir = cleaned;
            }
        } catch (Throwable e) {
            Log.w(TAG, "sanitizeNativeLibDir failed", e);
        }
    }
}
