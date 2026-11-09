package com.lody.virtual.helper.utils;

/**
 * Binder method arguments change width between Android releases
 * (e.g. PackageManager "flags" is int before Android 13 and long since).
 * Read them through this helper instead of casting to Integer.
 */
public final class NumArg {
    private NumArg() {
    }

    /** Narrow an Integer/Long argument to int. */
    public static int toInt(Object arg) {
        return ((Number) arg).intValue();
    }
}
