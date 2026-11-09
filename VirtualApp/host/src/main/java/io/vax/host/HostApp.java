package io.vax.host;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
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
