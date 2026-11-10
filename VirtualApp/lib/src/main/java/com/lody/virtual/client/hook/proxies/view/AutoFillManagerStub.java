package com.lody.virtual.client.hook.proxies.view;

import android.annotation.SuppressLint;
import android.util.Log;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import mirror.android.view.IAutoFillManager;

/**
 * @author 陈磊.
 */

public class AutoFillManagerStub extends BinderInvocationProxy {

    private static final String TAG = "AutoFillManagerStub";

    private static final String AUTO_FILL_NAME = "autofill";
    public AutoFillManagerStub() {
        super(IAutoFillManager.Stub.asInterface, AUTO_FILL_NAME);
    }

    @SuppressLint("WrongConstant")
    @Override
    public void inject() throws Throwable {
        super.inject();
        // Autofill session calls carry the caller package; rewrite it to the host so the real
        // service accepts them. These operate on the binder proxy installed by super.inject()
        // and must always be registered -- independent of the best-effort mService swap below,
        // which previously threw during early startup and skipped these entirely.
        addMethodProxy(new MethodProxy() {
            @Override
            public String getMethodName() {
                return "startSession";
            }
            @Override
            public boolean beforeCall(Object who, Method method, Object... args) {
                MethodParameterUtils.replaceLastAppPkg(args);
                return super.beforeCall(who, method, args);
            }
        });
        addMethodProxy(new MethodProxy() {
            @Override
            public String getMethodName() {
                return "updateOrRestartSession";
            }
            @Override
            public boolean beforeCall(Object who, Method method, Object... args) {
                MethodParameterUtils.replaceLastAppPkg(args);
                return super.beforeCall(who, method, args);
            }
        });
        addMethodProxy(new MethodProxy() {
            @Override
            public String getMethodName() {
                return "isServiceEnabled";
            }
            @Override
            public boolean beforeCall(Object who, Method method, Object... args) {
                MethodParameterUtils.replaceLastAppPkg(args);
                return super.beforeCall(who, method, args);
            }
        });
        // Best-effort: swap mService on an already-created AutofillManager. During
        // VirtualCore.startup (Application.attachBaseContext) the Application has no
        // ContentResolver yet, so on Android 12+ forcing the manager's creation throws an NPE
        // deep in AutofillManager.<init> (DeviceConfig feature-flag lookup). That is expected
        // and harmless: managers created later are covered by the binder-cache hook above.
        try {
            Object autoFillManager = getContext().getSystemService(AUTO_FILL_NAME);
            Object proxy = getInvocationStub().getProxyInterface();
            if (autoFillManager != null && proxy != null) {
                Field mService = autoFillManager.getClass().getDeclaredField("mService");
                mService.setAccessible(true);
                mService.set(autoFillManager, proxy);
            }
        } catch (Throwable tr) {
            Log.d(TAG, "autofill mService swap skipped (manager not ready yet): " + tr.getMessage());
        }
    }

}
