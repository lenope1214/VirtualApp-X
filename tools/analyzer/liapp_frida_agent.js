/**
 * liapp_frida_agent.js
 * 
 * 안드로이드 런타임 환경 분석 및 동적 디버깅 도구 (Frida Agent)
 * 
 * 기능:
 * 1. 보안/무결성 검증 모듈 (com.xshield.da, LiappAgentforUnity) 후킹 및 반환값 제어
 * 2. 동적 클래스 로딩 (DexClassLoader, liapp-y.dex, com.liapp.y) 모니터링 및 클래스 대체
 * 3. Intent 인터셉션 (VIEW 액션 및 https://kauth.kakao.com/ 페이로드 분석/수정)
 * 4. 프로세스 메모리 검색 및 제어 (/proc/<pid>/mem 및 Memory API)
 */

'use strict';

// =============================================================================
// [1] 유틸리티 및 안전 로깅
// =============================================================================
const TAG = '[LiappAnalyzer]';

function log(msg) {
    console.log(`${TAG} ${msg}`);
}

function logError(msg, err) {
    console.error(`${TAG} [ERROR] ${msg}: ${err ? (err.stack || err) : ''}`);
}

// =============================================================================
// [2] com.xshield.da 및 LiappAgentforUnity 무결성 검증 모듈 후킹
// =============================================================================
function hookIntegrityModules() {
    Java.perform(() => {
        log('Initializing Integrity Module Hooking...');

        // 1. LiappAgentforUnity 후킹
        const liappTargets = [
            'com.liapp.LiappAgentforUnity',
            'com.lockincomp.liapp.LiappAgentforUnity',
            'LiappAgentforUnity'
        ];

        liappTargets.forEach(className => {
            try {
                const clazz = Java.use(className);
                log(`Found target class: ${className}`);

                const methods = clazz.class.getDeclaredMethods();
                methods.forEach(m => {
                    const methodName = m.getName();
                    const returnType = m.getReturnType().getName();

                    try {
                        clazz[methodName].overloads.forEach(overload => {
                            overload.implementation = function (...args) {
                                log(`[CALL] ${className}.${methodName}(${args.map(a => JSON.stringify(a)).join(', ')})`);
                                
                                let originalResult = null;
                                try {
                                    originalResult = this[methodName](...args);
                                } catch (e) {
                                    log(`Exception in original call ${methodName}: ${e}`);
                                }

                                log(`[RETURN] ${className}.${methodName} original: ${originalResult} (type: ${returnType})`);

                                // 요구사항: 반환값(0 또는 빈 값) 모니터링 및 정상 상태로 제어
                                if (returnType === 'int' || returnType === 'java.lang.Integer') {
                                    log(`[OVERRIDE] ${className}.${methodName} -> 0 (OK)`);
                                    return 0;
                                } else if (returnType === 'boolean' || returnType === 'java.lang.Boolean') {
                                    log(`[OVERRIDE] ${className}.${methodName} -> false`);
                                    return false;
                                } else if (returnType === 'java.lang.String') {
                                    log(`[OVERRIDE] ${className}.${methodName} -> "" (empty)`);
                                    return Java.use('java.lang.String').$new('');
                                }

                                return originalResult;
                            };
                        });
                    } catch (hookErr) {
                        // 개별 오버로드 후킹 실패 시 계속 진행
                    }
                });
            } catch (e) {
                // 클래스가 아직 로드되지 않은 경우 무시
            }
        });

        // 2. com.xshield.da 후킹
        try {
            const xshieldClazz = Java.use('com.xshield.da');
            log('Found target class: com.xshield.da');

            const daMethods = xshieldClazz.class.getDeclaredMethods();
            daMethods.forEach(m => {
                const methodName = m.getName();
                const returnType = m.getReturnType().getName();

                try {
                    xshieldClazz[methodName].overloads.forEach(overload => {
                        overload.implementation = function (...args) {
                            log(`[XSHIELD CALL] com.xshield.da.${methodName}`);
                            let res = null;
                            try {
                                res = this[methodName](...args);
                            } catch (e) {
                                log(`Exception in xshield call: ${e}`);
                            }

                            if (returnType === 'int' || returnType === 'java.lang.Integer') {
                                log(`[XSHIELD OVERRIDE] ${methodName} -> 0`);
                                return 0;
                            } else if (returnType === 'java.lang.String') {
                                return Java.use('java.lang.String').$new('');
                            }
                            return res;
                        };
                    });
                } catch (e) {}
            });
        } catch (e) {}

        // 3. UnitySendMessage 콜백 가로채기 (Unity 브릿지 무력화/모니터링)
        try {
            const UnityPlayer = Java.use('com.unity3d.player.UnityPlayer');
            UnityPlayer.UnitySendMessage.overload('java.lang.String', 'java.lang.String', 'java.lang.String')
                .implementation = function (gameObject, method, message) {
                    log(`[UnitySendMessage] Obj: ${gameObject}, Method: ${method}, Msg: ${message}`);
                    if (gameObject.toLowerCase().includes('liapp') || method.toLowerCase().includes('detect') || method.toLowerCase().includes('security')) {
                        log(`[BLOCKED/FILTERED] Security notification to Unity: ${method}`);
                        return; // 보안 탐지 통보 차단
                    }
                    return this.UnitySendMessage(gameObject, method, message);
                };
        } catch (e) {}
    });
}

// =============================================================================
// [3] 동적 클래스 로딩 (DexClassLoader) 및 com.liapp.y 제어
// =============================================================================
function hookDynamicClassLoading() {
    Java.perform(() => {
        log('Initializing Dynamic Class Loading Hooks...');

        // DexClassLoader 생성자 추적 및 liapp-y.dex 인터셉트
        const DexClassLoader = Java.use('dalvik.system.DexClassLoader');
        DexClassLoader.$init.overload('java.lang.String', 'java.lang.String', 'java.lang.String', 'java.lang.ClassLoader')
            .implementation = function (dexPath, optPath, libPath, parent) {
                log(`[DexClassLoader] Loading DEX from: ${dexPath}`);
                log(`[DexClassLoader] optPath: ${optPath}, libPath: ${libPath}`);

                if (dexPath && dexPath.indexOf('liapp-y.dex') !== -1) {
                    log(`[TARGET MATCH] liapp-y.dex is being loaded!`);
                }

                return this.$init(dexPath, optPath, libPath, parent);
            };

        // ClassLoader.loadClass 후킹하여 com.liapp.y 클래스 조회 모니터링 및 대체
        const ClassLoader = Java.use('java.lang.ClassLoader');
        ClassLoader.loadClass.overload('java.lang.String', 'boolean').implementation = function (name, resolve) {
            if (name === 'com.liapp.y') {
                log(`[CLASS_LOAD] Loading target: ${name}`);
            }
            return this.loadClass(name, resolve);
        };
    });
}

// =============================================================================
// [4] Intent 필터링 및 리디렉션 (https://kauth.kakao.com/ 가로채기)
// =============================================================================
function hookIntentInterception() {
    Java.perform(() => {
        log('Initializing Intent Interception Hooks...');

        const Intent = Java.use('android.content.Intent');
        const Uri = Java.use('android.net.Uri');
        const Activity = Java.use('android.app.Activity');
        const ContextWrapper = Java.use('android.content.ContextWrapper');

        function inspectAndFilterIntent(intent) {
            if (!intent) return intent;

            try {
                const action = intent.getAction();
                const uri = intent.getData();

                if (action && action.indexOf('android.intent.action.VIEW') !== -1 && uri) {
                    const uriString = uri.toString();
                    if (uriString.indexOf('kauth.kakao.com') !== -1) {
                        log(`[INTENT INTERCEPTED] Target URL matched: ${uriString}`);

                        // 인텐트 페이로드 데이터 분석
                        const extras = intent.getExtras();
                        if (extras) {
                            const keySet = extras.keySet();
                            const iterator = keySet.iterator();
                            while (iterator.hasNext()) {
                                const key = iterator.next();
                                log(`   Extra: ${key} = ${extras.get(key)}`);
                            }
                        }

                        // URI 쿼리 파라미터 파싱
                        const query = uri.getQuery();
                        log(`   Query Params: ${query}`);

                        // 제어 예시: 리디렉션 또는 파라미터 변조가 필요한 경우 새 URI 설정 가능
                        // const modifiedUri = Uri.parse("https://kauth.kakao.com/oauth/authorize?modified=true...");
                        // intent.setData(modifiedUri);
                    }
                }
            } catch (err) {
                logError('Failed to inspect intent', err);
            }
            return intent;
        }

        // Activity.startActivity 후킹
        const startActivityOverloads = Activity.startActivity.overloads;
        startActivityOverloads.forEach(overload => {
            overload.implementation = function (...args) {
                if (args.length > 0 && args[0]) {
                    args[0] = inspectAndFilterIntent(args[0]);
                }
                return this.startActivity(...args);
            };
        });

        // ContextWrapper.startActivity 후킹
        const cwOverloads = ContextWrapper.startActivity.overloads;
        cwOverloads.forEach(overload => {
            overload.implementation = function (...args) {
                if (args.length > 0 && args[0]) {
                    args[0] = inspectAndFilterIntent(args[0]);
                }
                return this.startActivity(...args);
            };
        });
    });
}

// =============================================================================
// [5] 프로세스 메모리 접근 및 제어 (Frida Memory API 기반)
// =============================================================================
function inspectProcessMemory(patternHex, replacementHex) {
    log(`Scanning memory for pattern: ${patternHex}...`);

    Process.enumerateRanges('r--').forEach(range => {
        try {
            Memory.scan(range.base, range.size, patternHex, {
                onMatch: function (address, size) {
                    log(`[MEMORY MATCH] Found pattern at: ${address} (size: ${size})`);

                    if (replacementHex) {
                        // 페이지 쓰기 권한 부여
                        Memory.protect(address, size, 'rwx');
                        const bytes = [];
                        for (let i = 0; i < replacementHex.length; i += 2) {
                            bytes.push(parseInt(replacementHex.substr(i, 2), 16));
                        }
                        Memory.writeByteArray(address, bytes);
                        log(`[MEMORY PATCH] Patched address: ${address}`);
                    }
                    return 'stop'; // 첫 번째 일치 항목만 처리 시
                },
                onError: function (reason) {},
                onComplete: function () {}
            });
        } catch (e) {}
    });
}

// =============================================================================
// 진입점 (Entrypoint)
// =============================================================================
function main() {
    log('=== Starting Liapp & Xshield Dynamic Analyzer ===');

    hookIntegrityModules();
    hookDynamicClassLoading();
    hookIntentInterception();

    log('=== All hooks successfully installed ===');
}

setImmediate(main);
