package io.virtualapp.delegate;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;

import com.lody.virtual.client.hook.delegate.ComponentDelegate;
import com.lody.virtual.helper.utils.Reflect;

import java.io.File;


public class MyComponentDelegate implements ComponentDelegate {

    @Override
    public void beforeApplicationCreate(Application application) {
        // 게스트 앱 onCreate 직전(게스트 프로세스 내부). 배포된 패치 DEX가 있으면 주입, 없으면 no-op.
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
}
