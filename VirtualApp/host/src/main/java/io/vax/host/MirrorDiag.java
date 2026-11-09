package io.vax.host;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Lists mirror.* reference fields that stayed null on this Android version, i.e. hidden
 * classes/fields/methods the legacy VA code expects but the platform no longer has.
 * Run: am start -n io.vax.host/.MainActivity --ez diag true ; logcat -s VAXDIAG
 */
final class MirrorDiag {
    private static final String TAG = "VAXDIAG";

    static void run(Context ctx) {
        int nullCount = 0, total = 0, loadFail = 0;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open("mirror_classes.txt")))) {
            String name;
            while ((name = br.readLine()) != null) {
                name = name.trim();
                if (name.isEmpty()) continue;
                try {
                    Class<?> c = Class.forName(name);
                    int[] r = scan(c);
                    total += r[0];
                    nullCount += r[1];
                } catch (Throwable t) {
                    loadFail++;
                    Log.w(TAG, "LOADFAIL " + name + " : " + t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "diag failed", t);
        }
        Log.i(TAG, "DONE refs=" + total + " null=" + nullCount + " loadFail=" + loadFail);
    }

    private static int[] scan(Class<?> c) {
        int total = 0, nulls = 0;
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!f.getType().getName().startsWith("mirror.Ref")) continue;
            total++;
            try {
                f.setAccessible(true);
                if (f.get(null) == null) {
                    nulls++;
                    Log.i(TAG, "NULL " + c.getName() + "." + f.getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "ERR " + c.getName() + "." + f.getName() + " " + t);
            }
        }
        for (Class<?> inner : c.getDeclaredClasses()) {
            try {
                Class.forName(inner.getName());
                int[] r = scan(inner);
                total += r[0];
                nulls += r[1];
            } catch (Throwable t) {
                Log.w(TAG, "LOADFAIL " + inner.getName() + " : " + t);
            }
        }
        return new int[]{total, nulls};
    }
}
