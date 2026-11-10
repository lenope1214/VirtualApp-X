package io.vax.host;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.delegate.ComponentDelegate;
import com.lody.virtual.client.stub.VASettings;

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
                        }

                        @Override
                        public void afterApplicationCreate(Application application) {
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
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "VirtualCore.initialize FAILED", e);
        }
    }
}
