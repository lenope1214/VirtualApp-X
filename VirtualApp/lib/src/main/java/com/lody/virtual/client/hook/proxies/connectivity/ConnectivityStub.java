package com.lody.virtual.client.hook.proxies.connectivity;

import android.content.Context;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;
import com.lody.virtual.client.ipc.ServiceManagerNative;

import java.lang.reflect.Method;

import mirror.android.net.IConnectivityManager;

/**
 * @author legency
 */
public class ConnectivityStub extends BinderInvocationProxy {

    public ConnectivityStub() {
        super(IConnectivityManager.Stub.asInterface, Context.CONNECTIVITY_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplacePkgMethodProxy("getNetworkCapabilities"));
        addMethodProxy(new ReplacePkgMethodProxy("getLinkProperties"));
        addMethodProxy(new ReplacePkgMethodProxy("getNetworkInfo"));
        addMethodProxy(new ReplacePkgMethodProxy("getAllNetworkInfo"));
        addMethodProxy(new ReplacePkgMethodProxy("getActiveNetworkInfo"));
        addMethodProxy(new ReplacePkgMethodProxy("getAllNetworks"));
        addMethodProxy(new ReplacePkgMethodProxy("registerNetworkCallback"));
        addMethodProxy(new ReplacePkgMethodProxy("listenForNetwork"));
        addMethodProxy(new ReplacePkgMethodProxy("requestNetwork"));
    }

    private static class ReplacePkgMethodProxy extends StaticMethodProxy {
        ReplacePkgMethodProxy(String name) {
            super(name);
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            if (args != null) {
                String appPkg = getAppPkg();
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof String) {
                        String s = (String) args[i];
                        if (appPkg != null && appPkg.equals(s)) {
                            args[i] = getHostPkg();
                        }
                    }
                }
            }
            return super.beforeCall(who, method, args);
        }
    }
}
