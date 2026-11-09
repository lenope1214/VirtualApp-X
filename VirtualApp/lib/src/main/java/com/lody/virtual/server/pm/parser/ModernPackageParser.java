package com.lody.virtual.server.pm.parser;

import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.PatternMatcher;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Package parser for Android 11+, where android.content.pm.PackageParser is gone/reshaped.
 *
 * Component metadata and signatures come from the public
 * {@link PackageManager#getPackageArchiveInfo}. That API does not expose intent filters,
 * uses-library or protected-broadcast, so those are read straight from the binary
 * AndroidManifest.xml through an AssetManager.
 */
final class ModernPackageParser {
    private static final String TAG = "ModernPackageParser";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private ModernPackageParser() {
    }

    @SuppressWarnings("deprecation")
    static VPackage parse(File apk) throws Throwable {
        PackageManager pm = VirtualCore.get().getUnHookPackageManager();
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS
                | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS
                | PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS
                | PackageManager.GET_INSTRUMENTATION | PackageManager.GET_CONFIGURATIONS
                | PackageManager.GET_SHARED_LIBRARY_FILES
                | PackageManager.MATCH_DISABLED_COMPONENTS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        } else {
            flags |= PackageManager.GET_SIGNATURES;
        }
        String path = apk.getPath();
        PackageInfo pi = pm.getPackageArchiveInfo(path, flags);
        if (pi == null || pi.packageName == null) {
            throw new IllegalStateException("getPackageArchiveInfo returned null for " + path);
        }
        ApplicationInfo ai = pi.applicationInfo;
        ai.sourceDir = path;
        ai.publicSourceDir = path;

        VPackage v = new VPackage();
        v.packageName = pi.packageName;
        v.mVersionName = pi.versionName;
        v.mVersionCode = (int) (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? pi.getLongVersionCode() : pi.versionCode);
        v.mSharedUserId = pi.sharedUserId;
        v.mSharedUserLabel = pi.sharedUserLabel;
        v.applicationInfo = ai;
        v.mAppMetaData = ai.metaData;
        v.mSignatures = signaturesOf(pi);

        v.requestedPermissions = new ArrayList<>();
        if (pi.requestedPermissions != null) {
            for (String perm : pi.requestedPermissions) {
                v.requestedPermissions.add(perm);
            }
        }
        v.protectedBroadcasts = new ArrayList<>();
        v.usesLibraries = new ArrayList<>();

        v.activities = new ArrayList<>();
        v.receivers = new ArrayList<>();
        v.services = new ArrayList<>();
        v.providers = new ArrayList<>();
        v.instrumentation = new ArrayList<>();
        v.permissions = new ArrayList<>();
        v.permissionGroups = new ArrayList<>();

        Map<String, VPackage.ActivityComponent> activityByName = new HashMap<>();
        Map<String, VPackage.ActivityComponent> receiverByName = new HashMap<>();
        Map<String, VPackage.ServiceComponent> serviceByName = new HashMap<>();
        Map<String, VPackage.ProviderComponent> providerByName = new HashMap<>();

        if (pi.activities != null) {
            for (ActivityInfo info : pi.activities) {
                VPackage.ActivityComponent c = new VPackage.ActivityComponent(info, info.metaData);
                v.activities.add(c);
                activityByName.put(info.name, c);
            }
        }
        if (pi.receivers != null) {
            for (ActivityInfo info : pi.receivers) {
                VPackage.ActivityComponent c = new VPackage.ActivityComponent(info, info.metaData);
                v.receivers.add(c);
                receiverByName.put(info.name, c);
            }
        }
        if (pi.services != null) {
            for (ServiceInfo info : pi.services) {
                VPackage.ServiceComponent c = new VPackage.ServiceComponent(info, info.metaData);
                v.services.add(c);
                serviceByName.put(info.name, c);
            }
        }
        if (pi.providers != null) {
            for (ProviderInfo info : pi.providers) {
                VPackage.ProviderComponent c = new VPackage.ProviderComponent(info, info.metaData);
                v.providers.add(c);
                providerByName.put(info.name, c);
            }
        }
        if (pi.instrumentation != null) {
            for (InstrumentationInfo info : pi.instrumentation) {
                v.instrumentation.add(new VPackage.InstrumentationComponent(info));
            }
        }
        if (pi.permissions != null) {
            for (PermissionInfo info : pi.permissions) {
                v.permissions.add(new VPackage.PermissionComponent(info));
            }
        }
        if (pi.configPreferences != null) {
            v.configPreferences = new ArrayList<>();
            for (ConfigurationInfo c : pi.configPreferences) {
                v.configPreferences.add(c);
            }
        }
        if (pi.reqFeatures != null) {
            v.reqFeatures = new ArrayList<>();
            for (FeatureInfo f : pi.reqFeatures) {
                v.reqFeatures.add(f);
            }
        }

        try {
            parseManifest(path, v, activityByName, receiverByName, serviceByName, providerByName);
        } catch (Throwable e) {
            // The package is still usable for explicit launches without intent filters.
            VLog.e(TAG, "manifest parse failed for " + v.packageName + ": " + e);
        }
        PackageParserEx.addOwner(v);
        return v;
    }

    private static Signature[] signaturesOf(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pi.signingInfo != null) {
            SigningInfo si = pi.signingInfo;
            Signature[] sigs = si.hasMultipleSigners() ? si.getApkContentsSigners()
                    : si.getSigningCertificateHistory();
            if (sigs != null && sigs.length > 0) {
                return sigs;
            }
        }
        return pi.signatures;
    }

    /** Open AndroidManifest.xml of an arbitrary apk (hidden-API route, same as plugin frameworks). */
    private static XmlResourceParser openManifest(String apkPath) throws Exception {
        Constructor<AssetManager> ctor = AssetManager.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        AssetManager am = ctor.newInstance();
        Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        addAssetPath.setAccessible(true);
        int cookie = (Integer) addAssetPath.invoke(am, apkPath);
        if (cookie == 0) {
            throw new IllegalStateException("addAssetPath failed for " + apkPath);
        }
        return am.openXmlResourceParser(cookie, "AndroidManifest.xml");
    }

    private static String resolveName(String pkg, String name) {
        if (name == null) return null;
        if (name.startsWith(".")) return pkg + name;
        if (name.indexOf('.') < 0) return pkg + "." + name;
        return name;
    }

    private static void parseManifest(String apkPath, VPackage v,
                                      Map<String, VPackage.ActivityComponent> activities,
                                      Map<String, VPackage.ActivityComponent> receivers,
                                      Map<String, VPackage.ServiceComponent> services,
                                      Map<String, VPackage.ProviderComponent> providers) throws Exception {
        XmlResourceParser xml = openManifest(apkPath);
        try {
            // what the enclosing component is, so <intent-filter> knows where to attach
            String kind = null;          // activity | receiver | service | provider
            String componentName = null;
            IntentFilter filter = null;
            boolean hasDefault = false;
            int fLabelRes = 0, fIcon = 0;
            String fLabel = null;

            int type;
            while ((type = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (type == XmlResourceParser.START_TAG) {
                    String tag = xml.getName();
                    switch (tag) {
                        case "activity":
                        case "activity-alias":
                            kind = "activity";
                            componentName = resolveName(v.packageName, xml.getAttributeValue(ANDROID_NS, "name"));
                            break;
                        case "receiver":
                            kind = "receiver";
                            componentName = resolveName(v.packageName, xml.getAttributeValue(ANDROID_NS, "name"));
                            break;
                        case "service":
                            kind = "service";
                            componentName = resolveName(v.packageName, xml.getAttributeValue(ANDROID_NS, "name"));
                            break;
                        case "provider":
                            kind = "provider";
                            componentName = resolveName(v.packageName, xml.getAttributeValue(ANDROID_NS, "name"));
                            break;
                        case "uses-library": {
                            String n = xml.getAttributeValue(ANDROID_NS, "name");
                            if (n != null) v.usesLibraries.add(n);
                            break;
                        }
                        case "protected-broadcast": {
                            String n = xml.getAttributeValue(ANDROID_NS, "name");
                            if (n != null) v.protectedBroadcasts.add(n);
                            break;
                        }
                        case "intent-filter":
                            filter = new IntentFilter();
                            filter.setPriority(xml.getAttributeIntValue(ANDROID_NS, "priority", 0));
                            hasDefault = false;
                            fLabelRes = xml.getAttributeResourceValue(ANDROID_NS, "label", 0);
                            fIcon = xml.getAttributeResourceValue(ANDROID_NS, "icon", 0);
                            fLabel = fLabelRes == 0 ? xml.getAttributeValue(ANDROID_NS, "label") : null;
                            break;
                        case "action":
                            if (filter != null) {
                                String n = xml.getAttributeValue(ANDROID_NS, "name");
                                if (n != null) filter.addAction(n);
                            }
                            break;
                        case "category":
                            if (filter != null) {
                                String n = xml.getAttributeValue(ANDROID_NS, "name");
                                if (n != null) {
                                    filter.addCategory(n);
                                    if ("android.intent.category.DEFAULT".equals(n)) hasDefault = true;
                                }
                            }
                            break;
                        case "data":
                            if (filter != null) readData(xml, filter);
                            break;
                        default:
                            break;
                    }
                } else if (type == XmlResourceParser.END_TAG) {
                    String tag = xml.getName();
                    if ("intent-filter".equals(tag) && filter != null && kind != null) {
                        attach(kind, componentName, filter, hasDefault, fLabelRes, fLabel, fIcon,
                                activities, receivers, services, providers);
                        filter = null;
                    } else if ("activity".equals(tag) || "activity-alias".equals(tag)
                            || "receiver".equals(tag) || "service".equals(tag)
                            || "provider".equals(tag)) {
                        kind = null;
                        componentName = null;
                    }
                }
            }
        } finally {
            xml.close();
        }
    }

    private static void readData(XmlResourceParser xml, IntentFilter filter) {
        String scheme = xml.getAttributeValue(ANDROID_NS, "scheme");
        if (scheme != null) filter.addDataScheme(scheme);

        String host = xml.getAttributeValue(ANDROID_NS, "host");
        String port = xml.getAttributeValue(ANDROID_NS, "port");
        if (host != null) filter.addDataAuthority(host, port);

        String path = xml.getAttributeValue(ANDROID_NS, "path");
        if (path != null) filter.addDataPath(path, PatternMatcher.PATTERN_LITERAL);
        String prefix = xml.getAttributeValue(ANDROID_NS, "pathPrefix");
        if (prefix != null) filter.addDataPath(prefix, PatternMatcher.PATTERN_PREFIX);
        String glob = xml.getAttributeValue(ANDROID_NS, "pathPattern");
        if (glob != null) filter.addDataPath(glob, PatternMatcher.PATTERN_SIMPLE_GLOB);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String adv = xml.getAttributeValue(ANDROID_NS, "pathAdvancedPattern");
            if (adv != null) filter.addDataPath(adv, PatternMatcher.PATTERN_ADVANCED_GLOB);
            String suffix = xml.getAttributeValue(ANDROID_NS, "pathSuffix");
            if (suffix != null) filter.addDataPath(suffix, PatternMatcher.PATTERN_SUFFIX);
        }

        String mime = xml.getAttributeValue(ANDROID_NS, "mimeType");
        if (mime != null) {
            try {
                filter.addDataType(mime);
            } catch (IntentFilter.MalformedMimeTypeException e) {
                VLog.w(TAG, "bad mimeType " + mime);
            }
        }
    }

    private static void attach(String kind, String name, IntentFilter filter, boolean hasDefault,
                               int labelRes, String label, int icon,
                               Map<String, VPackage.ActivityComponent> activities,
                               Map<String, VPackage.ActivityComponent> receivers,
                               Map<String, VPackage.ServiceComponent> services,
                               Map<String, VPackage.ProviderComponent> providers) {
        if (name == null) return;
        switch (kind) {
            case "activity": {
                VPackage.ActivityComponent c = activities.get(name);
                if (c != null) c.intents.add(new VPackage.ActivityIntentInfo(filter, hasDefault, labelRes, label, icon));
                break;
            }
            case "receiver": {
                VPackage.ActivityComponent c = receivers.get(name);
                if (c != null) c.intents.add(new VPackage.ActivityIntentInfo(filter, hasDefault, labelRes, label, icon));
                break;
            }
            case "service": {
                VPackage.ServiceComponent c = services.get(name);
                if (c != null) c.intents.add(new VPackage.ServiceIntentInfo(filter, hasDefault, labelRes, label, icon));
                break;
            }
            case "provider": {
                VPackage.ProviderComponent c = providers.get(name);
                if (c != null) c.intents.add(new VPackage.ProviderIntentInfo(filter, hasDefault, labelRes, label, icon));
                break;
            }
            default:
                break;
        }
    }
}
