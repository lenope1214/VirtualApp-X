package io.vax.analyzer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * IntentInterceptor
 * 
 * 특정 외부 URI(https://kauth.kakao.com/)로 향하는 VIEW 액션 인텐트를 가로채고,
 * 인텐트 페이로드 데이터 분석 및 제어를 수행하는 인터셉터 모듈.
 * 
 * VirtualApp의 MethodProxies.StartActivity 또는 프레임워크 레벨 훅 지점에서 호출하여 사용.
 */
public class IntentInterceptor {

    private static final String TAG = "IntentInterceptor";
    private static final String TARGET_HOST = "kauth.kakao.com";
    private static final String TARGET_ACTION = Intent.ACTION_VIEW;

    public interface IntentCallback {
        /**
         * 타깃 인텐트 감지 시 호출되며, 수정된 Intent를 반환하거나 null을 반환하여 인텐트 중단 가능.
         */
        Intent onIntercept(Intent originalIntent, Uri uri, Map<String, String> queryParams, Bundle extras);
    }

    private static IntentCallback sCallback;

    public static void setCallback(IntentCallback callback) {
        sCallback = callback;
    }

    /**
     * StartActivity 호출 시 인텐트를 검사하고 필터링/변조 수행.
     *
     * @param intent 원본 인텐트
     * @return 검사 및 수정이 완료된 인텐트 (null일 경우 호출 차단)
     */
    public static Intent processIntent(Intent intent) {
        if (intent == null) {
            return null;
        }

        try {
            String action = intent.getAction();
            Uri uri = intent.getData();

            // 1. Intent.ACTION_VIEW 액션 및 대상 호스트 일치 여부 검증
            if (TARGET_ACTION.equals(action) && uri != null) {
                String host = uri.getHost();
                if (host != null && host.equalsIgnoreCase(TARGET_HOST)) {
                    Log.i(TAG, ">>> Target Intent intercepted! URI: " + uri.toString());

                    // 2. URI 쿼리 파라미터 파싱 및 분석
                    Map<String, String> queryParams = new HashMap<>();
                    if (uri.isHierarchical()) {
                        Set<String> parameterNames = uri.getQueryParameterNames();
                        for (String paramName : parameterNames) {
                            String value = uri.getQueryParameter(paramName);
                            queryParams.put(paramName, value);
                            Log.i(TAG, "    Query: " + paramName + " = " + value);
                        }
                    }

                    // 3. 인텐트 Extras 페이로드 분석
                    Bundle extras = intent.getExtras();
                    if (extras != null) {
                        for (String key : extras.keySet()) {
                            Object val = extras.get(key);
                            Log.i(TAG, "    Extra: " + key + " = " + val);
                        }
                    }

                    // 4. 커스텀 콜백이 지정되어 있다면 제어 위임
                    if (sCallback != null) {
                        Intent modified = sCallback.onIntercept(intent, uri, queryParams, extras);
                        Log.i(TAG, "Intent modified by callback: " + (modified != null ? modified.toString() : "DROPPED"));
                        return modified;
                    }

                    // 5. 기본 제어 로직: 필요 시 파라미터 검증 또는 리디렉션 처리
                    // 예: 특정 파라미터(client_id, redirect_uri 등)를 캡처하여 내부 인증 로직에 전달
                    return handleDefaultInterception(intent, uri, queryParams);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Exception during intent interception: " + t.getMessage(), t);
        }

        // 대상이 아닌 일반 인텐트는 그대로 전달
        return intent;
    }

    /**
     * 기본 인터셉션 처리 로직 예시 (로그 기록 및 안전한 전달/변조)
     */
    private static Intent handleDefaultInterception(Intent intent, Uri uri, Map<String, String> queryParams) {
        String clientId = queryParams.get("client_id");
        String redirectUri = queryParams.get("redirect_uri");
        String responseType = queryParams.get("response_type");

        Log.i(TAG, String.format("[OAuth Intercept] ClientID: %s, RedirectURI: %s, ResponseType: %s",
                clientId, redirectUri, responseType));

        // 예: 인증 흐름을 웹뷰나 로컬 가상 브라우저로 리디렉션하거나 페이로드를 유지하여 전달
        return intent;
    }
}
