package io.vax.host;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lody.virtual.client.core.InstallStrategy;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.remote.InstallResult;

/**
 * Test harness. Drive it from adb:
 *   am start -n io.vax.host/.MainActivity --es install <pkg> --es launch <pkg>
 * "install" clones an app that is already installed on the device into the virtual space.
 */
public class MainActivity extends Activity {
    static final String TAG = "VAX";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);
        TextView tv = new TextView(this);
        tv.setText("VA-X host — tap an app to launch (no adb needed)");
        root.addView(tv);
        // Tap launcher: lets a guest be launched by finger so the device's USB-debugging
        // state is irrelevant to how the launch is triggered. Driving it from adb forces
        // adb_enabled on; tapping here does not.
        populateLauncher(root);
        scroll.addView(root);
        setContentView(scroll);
        handle(getIntent());
    }

    private void populateLauncher(LinearLayout root) {
        try {
            java.util.List<com.lody.virtual.remote.InstalledAppInfo> apps =
                    VirtualCore.get().getInstalledApps(0);
            for (com.lody.virtual.remote.InstalledAppInfo info : apps) {
                final String pkg = info.packageName;
                android.widget.Button b = new android.widget.Button(this);
                b.setText(pkg);
                b.setOnClickListener(v -> new Thread(() -> {
                    try {
                        doLaunch(pkg);
                    } catch (Throwable t) {
                        Log.e(TAG, "launch failed for " + pkg, t);
                    }
                }, "vax-launch").start());
                root.addView(b);
            }
        } catch (Throwable t) {
            Log.e(TAG, "populateLauncher failed", t);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (intent.getBooleanExtra("diag", false)) {
            new Thread(() -> MirrorDiag.run(getApplicationContext()), "vax-diag").start();
        }
        final String install = intent.getStringExtra("install");
        final String launch = intent.getStringExtra("launch");
        if (install == null && launch == null) return;
        new Thread(() -> {
            try {
                if (install != null) doInstall(install);
                if (launch != null) doLaunch(launch);
            } catch (Throwable t) {
                Log.e(TAG, "action failed", t);
            }
        }, "vax-action").start();
    }

    private void doInstall(String pkg) throws Exception {
        ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
        Log.i(TAG, "install " + pkg + " from " + ai.sourceDir);
        // Split apps are installed from their directory (base.apk + split_*.apk).
        String path = ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0
                ? new java.io.File(ai.sourceDir).getParent() : ai.sourceDir;
        Log.i(TAG, "install path " + path + " splits=" + (ai.splitSourceDirs == null ? 0 : ai.splitSourceDirs.length));
        InstallResult r = VirtualCore.get().installPackage(path, InstallStrategy.UPDATE_IF_EXIST);
        Log.i(TAG, "install result: success=" + (r != null && r.isSuccess)
                + " pkg=" + (r == null ? null : r.packageName)
                + " error=" + (r == null ? null : r.error));
        if (r != null && r.isSuccess) {
            // Isolate the guest's external storage into a per-package sandbox dir. Without this the
            // guest (running as the host uid) hits EPERM on /sdcard under scoped storage. The vs
            // path lives under the host's INTERNAL files dir, not under /sdcard, so it is never a
            // target of the /sdcard redirect itself (no recursive relocation) and the host uid can
            // write it freely.
            java.io.File vs = new java.io.File(getFilesDir(), "vstorage/" + pkg);
            vs.mkdirs();
            com.lody.virtual.client.ipc.VirtualStorageManager vsm =
                    com.lody.virtual.client.ipc.VirtualStorageManager.get();
            vsm.setVirtualStorage(pkg, 0, vs.getAbsolutePath());
            vsm.setVirtualStorageState(pkg, 0, true);
            Log.i(TAG, "virtual storage enabled for " + pkg + " -> " + vs.getAbsolutePath());
        }
    }

    private void doLaunch(String pkg) {
        Intent i = VirtualCore.get().getLaunchIntent(pkg, 0);
        Log.i(TAG, "launch intent for " + pkg + " = " + i);
        if (i != null) {
            int res = VActivityManager.get().startActivity(i, 0);
            Log.i(TAG, "startActivity result=" + res);
        }
    }
}
