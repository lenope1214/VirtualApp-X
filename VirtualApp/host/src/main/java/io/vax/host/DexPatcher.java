package io.vax.host;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.nio.ByteBuffer;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.InMemoryDexClassLoader;

/**
 * DexPatcher — 게스트 프로세스 런타임에서 LIAPP 보호 앱의 암호화된 에셋에서
 * DEX를 XOR 복호화하여 {@code code_cache/liapp-y.dex}로 기록하고,
 * 앱 ClassLoader의 dexElements 맨 앞에 주입한다.
 *
 * <h3>동작 흐름</h3>
 * <ol>
 *   <li>code_cache에 이미 복호화된 liapp-y.dex가 있으면 → 바로 주입 (캐시 히트)</li>
 *   <li>없으면 → 게스트 APK(base.apk)의 {@code assets/z/c4a19e2b}(암호화 DEX)와
 *       {@code assets/z/8f3d70a1}(암호화 IL2CPP metadata)를 읽어 16바이트 XOR 복호화</li>
 *   <li>IL2CPP metadata 매직(0xFAB11BAF)으로 키 역산, DEX 매직(dex\n)으로 검증</li>
 *   <li>복호화된 DEX를 code_cache에 0444로 저장 후 DexClassLoader로 주입</li>
 *   <li>복호화된 IL2CPP metadata는 files/il2cpp/Metadata/global-metadata.dat에 저장</li>
 * </ol>
 *
 * 에셋이 없는 일반 앱에서는 모든 경로가 no-op.
 * Android 14+ W^X 보안 정책 대응(chmod 0444).
 */
public final class DexPatcher {

    private static final String TAG = "VAX-DexPatcher";
    private static final String PATCH_DEX = "liapp-y.dex";

    private static final String[] DEX_ASSET_NAMES = {
            "assets/.yfbyaux.dex",
            "assets/z/c4a19e2b",
    };
    private static final String ENCRYPTED_META_ASSET = "assets/z/8f3d70a1";

    // IL2CPP metadata 매직: 0xFAB11BAF (Little Endian: AF 1B B1 FA)
    private static final byte[] IL2CPP_MAGIC = {(byte) 0xAF, 0x1B, (byte) 0xB1, (byte) 0xFA};
    // DEX 매직
    private static final byte[] DEX_MAGIC = {'d', 'e', 'x', '\n'};

    private DexPatcher() {}

    /**
     * 게스트 Application.onCreate 직전(beforeApplicationCreate)에 호출.
     * 암호화 에셋이 있으면 XOR 복호화 → DEX 주입.
     * 없거나 실패해도 앱 부팅은 그대로 진행(throw 안 함).
     */
    public static void injectIfPresent(Context context) {
        if (context == null) return;
        try {
            dumpEnvironmentDiag(context);

            File codeCacheDir = context.getCodeCacheDir();
            File dexFile = new File(codeCacheDir, PATCH_DEX);

            Log.i(TAG, "injectIfPresent: pkg=" + context.getPackageName()
                    + " dex=" + dexFile.getAbsolutePath() + " exists=" + dexFile.isFile());

            // 캐시에 없으면 에셋에서 복호화 시도
            if (!dexFile.isFile() || dexFile.length() == 0) {
                boolean decrypted = decryptFromApkAssets(context, dexFile);
                if (!decrypted) {
                    Log.d(TAG, "No encrypted assets found or decryption failed; no-op for this app.");
                    return;
                }
            }

            // 파일이 있으면 ClassLoader에 주입
            if (!dexFile.isFile() || dexFile.length() == 0) return;

            ClassLoader target = context.getClassLoader();
            Log.i(TAG, "Target ClassLoader: " + target.getClass().getName()
                    + "@" + Integer.toHexString(System.identityHashCode(target)));
            if (!(target instanceof BaseDexClassLoader)) {
                Log.w(TAG, "App ClassLoader is not BaseDexClassLoader: " + target);
                return;
            }

            BaseDexClassLoader patchCl = createPatchClassLoader(context, dexFile, target);
            if (patchCl == null) {
                Log.e(TAG, "Failed to create patch ClassLoader");
                return;
            }

            if (injectFront((BaseDexClassLoader) target, patchCl)) {
                Log.i(TAG, "Injected patch DEX into app ClassLoader: " + dexFile.getAbsolutePath());
            } else {
                Log.w(TAG, "Patch DEX load OK but dexElements injection failed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "injectIfPresent failed (app boot continues)", t);
        }
    }

    // =========================================================================
    // 에셋 XOR 복호화
    // =========================================================================

    /**
     * 게스트 APK에서 암호화 에셋을 읽어 XOR 복호화. 키는 IL2CPP 매직 시그니처로부터 역산.
     *
     * @return true: 복호화 성공 및 dexFile 기록 완료
     */
    private static boolean decryptFromApkAssets(Context context, File dexFile) {
        String sourceDir = context.getApplicationInfo().sourceDir;
        if (sourceDir == null) return false;

        ZipFile zip = null;
        try {
            zip = new ZipFile(sourceDir);

            byte[] rawDex = null;
            String foundName = null;
            for (String name : DEX_ASSET_NAMES) {
                rawDex = readZipEntry(zip, name);
                if (rawDex != null) { foundName = name; break; }
            }
            if (rawDex == null) {
                Log.d(TAG, "No LIAPP DEX asset found in APK.");
                return false;
            }
            Log.i(TAG, "Found LIAPP DEX asset: " + foundName + " (" + rawDex.length + " bytes)");

            byte[] dexBytes = extractDex(rawDex);
            if (dexBytes == null) {
                // Try XOR decryption as fallback
                byte[] key = deriveKeyFromHeader(rawDex, DEX_MAGIC, 16);
                dexBytes = xorDecrypt(rawDex, key);
                if (!isDexMagic(dexBytes) || !isDexSizeValid(dexBytes)) {
                    byte[] encMeta = readZipEntry(zip, ENCRYPTED_META_ASSET);
                    if (encMeta != null) {
                        key = deriveKeyFromHeader(encMeta, IL2CPP_MAGIC, 16);
                        dexBytes = xorDecrypt(rawDex, key);
                        if (isDexMagic(dexBytes) && isDexSizeValid(dexBytes)) {
                            byte[] decMeta = xorDecrypt(encMeta, key);
                            if (isDexMagic(decMeta, IL2CPP_MAGIC)) deployMetadata(context, decMeta);
                        }
                    }
                    if (!isDexMagic(dexBytes) || !isDexSizeValid(dexBytes)) {
                        Log.e(TAG, "All DEX extraction methods failed.");
                        return false;
                    }
                }
            }

            Log.i(TAG, "DEX ready. Size=" + dexBytes.length);
            writeDexFile(dexFile, dexBytes);
            return true;

        } catch (Throwable t) {
            Log.e(TAG, "decryptFromApkAssets failed", t);
            return false;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static byte[] extractDex(byte[] raw) {
        if (!isDexMagic(raw) || raw.length < 112) return null;

        int outerSize = readLittleEndianInt(raw, 0x20);
        Log.i(TAG, "Outer DEX file_size=" + outerSize + ", actual=" + raw.length);

        // Plain valid DEX: header size matches data
        if (outerSize == raw.length) return raw;

        // Packed format: stub DEX + inner payload
        if (outerSize >= raw.length || outerSize < 112) return null;

        int innerOff = outerSize;
        int payloadLen = raw.length - innerOff;

        // Check if inner data starts with DEX magic
        if (payloadLen > 112 && isDexMagicAt(raw, innerOff)) {
            int innerSize = readLittleEndianInt(raw, innerOff + 0x20);
            Log.i(TAG, "Inner DEX found at offset " + innerOff
                    + ": version=" + new String(raw, innerOff + 4, 3)
                    + ", claimed_size=" + innerSize + ", available=" + payloadLen);

            // If inner size matches available data, it's uncompressed
            if (innerSize == payloadLen) {
                byte[] inner = new byte[payloadLen];
                System.arraycopy(raw, innerOff, inner, 0, payloadLen);
                return inner;
            }

            // Inner DEX is compressed — header is plain, body is compressed
            byte[] header = new byte[112];
            System.arraycopy(raw, innerOff, header, 0, 112);

            byte[] compressed = new byte[payloadLen - 112];
            System.arraycopy(raw, innerOff + 112, compressed, 0, compressed.length);

            int bodySize = innerSize - 112;
            byte[] body = tryInflate(compressed, bodySize);
            if (body != null) {
                Log.i(TAG, "Inner DEX body inflated: " + compressed.length + " → " + body.length);
                byte[] full = new byte[112 + body.length];
                System.arraycopy(header, 0, full, 0, 112);
                System.arraycopy(body, 0, full, 112, body.length);
                if (isDexMagic(full)) return full;
            }

            // Try inflating the entire inner payload (header+body compressed together)
            byte[] entirePayload = new byte[payloadLen];
            System.arraycopy(raw, innerOff, entirePayload, 0, payloadLen);
            byte[] inflatedFull = tryInflate(entirePayload, innerSize);
            if (inflatedFull != null && isDexMagic(inflatedFull)) {
                Log.i(TAG, "Entire inner payload inflated: " + payloadLen + " → " + inflatedFull.length);
                return inflatedFull;
            }

            // Try decompressing just the payload without header
            byte[] fromPayload = tryInflate(compressed, 0);
            if (fromPayload != null && fromPayload.length > 112 && isDexMagic(fromPayload)) {
                Log.i(TAG, "Payload inflated to standalone DEX: " + fromPayload.length);
                return fromPayload;
            }

            Log.w(TAG, "Inner DEX decompression failed, trying XOR on inner");
            // Try XOR on the inner data
            byte[] innerRaw = new byte[payloadLen];
            System.arraycopy(raw, innerOff, innerRaw, 0, payloadLen);
            byte[] key = deriveKeyFromHeader(compressed, DEX_MAGIC, 16);
            byte[] xored = xorDecrypt(innerRaw, key);
            if (isDexMagic(xored) && isDexSizeValid(xored)) return xored;
        }

        // No inner DEX header — try inflating the entire payload after stub
        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, innerOff, payload, 0, payloadLen);
        byte[] inflated = tryInflate(payload, 0);
        if (inflated != null && isDexMagic(inflated)) {
            Log.i(TAG, "Raw payload inflated to DEX: " + inflated.length);
            return inflated;
        }

        return null;
    }

    private static byte[] tryInflate(byte[] compressed, int expectedSize) {
        // Try raw inflate (no zlib header)
        byte[] result = tryInflateMode(compressed, expectedSize, true);
        if (result != null) return result;
        // Try zlib inflate (with header)
        return tryInflateMode(compressed, expectedSize, false);
    }

    private static byte[] tryInflateMode(byte[] compressed, int expectedSize, boolean nowrap) {
        Inflater inf = new Inflater(nowrap);
        try {
            inf.setInput(compressed);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    expectedSize > 0 ? expectedSize : compressed.length * 4);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) break;
                bos.write(buf, 0, n);
            }
            byte[] out = bos.toByteArray();
            if (out.length < 112) return null;
            Log.d(TAG, "tryInflate(nowrap=" + nowrap + "): " + compressed.length + " → " + out.length
                    + (expectedSize > 0 ? " (expected " + expectedSize + ")" : ""));
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            inf.end();
        }
    }

    private static boolean isDexSizeValid(byte[] data) {
        if (data == null || data.length < 0x24) return false;
        int claimed = readLittleEndianInt(data, 0x20);
        return claimed == data.length;
    }

    private static boolean isDexMagicAt(byte[] data, int off) {
        return off + 4 <= data.length
                && data[off] == 'd' && data[off + 1] == 'e' && data[off + 2] == 'x' && data[off + 3] == '\n';
    }

    private static int readLittleEndianInt(byte[] data, int off) {
        return (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }

    private static boolean isDexMagic(byte[] data) {
        return isDexMagic(data, DEX_MAGIC);
    }

    private static boolean isDexMagic(byte[] data, byte[] magic) {
        if (data == null || data.length < 4) return false;
        return data[0] == magic[0] && data[1] == magic[1] && data[2] == magic[2] && data[3] == magic[3];
    }

    /** ZipFile에서 엔트리 바이트 읽기. 없으면 null. */
    private static byte[] readZipEntry(ZipFile zip, String entryPath) throws IOException {
        // 에셋 경로는 APK 기준 "assets/..." 형식
        ZipEntry entry = zip.getEntry(entryPath);
        if (entry == null) {
            // .yfbyaux.dex 같은 숨은 이름 패턴도 탐색
            entry = zip.getEntry(entryPath.replace("assets/", ""));
            if (entry == null) return null;
        }
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] buf = new byte[(int) entry.getSize()];
            int off = 0;
            while (off < buf.length) {
                int n = is.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return buf;
        }
    }

    /** 16바이트 주기적 XOR 복호화. */
    private static byte[] xorDecrypt(byte[] data, byte[] key) {
        int keyLen = key.length;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key[i % keyLen]);
        }
        return out;
    }

    /** 알려진 헤더 매직으로부터 XOR 키 역산. */
    private static byte[] deriveKeyFromHeader(byte[] encData, byte[] knownHeader, int keyLen) {
        byte[] key = new byte[keyLen];
        for (int i = 0; i < Math.min(knownHeader.length, keyLen); i++) {
            key[i] = (byte) (encData[i] ^ knownHeader[i]);
        }
        return key;
    }

    /** 복호화된 IL2CPP metadata를 게스트 앱의 files/ 디렉터리에 배포. */
    private static void deployMetadata(Context context, byte[] decMeta) {
        try {
            File metaDir = new File(context.getFilesDir(), "il2cpp/Metadata");
            metaDir.mkdirs();
            File metaFile = new File(metaDir, "global-metadata.dat");
            try (FileOutputStream fos = new FileOutputStream(metaFile)) {
                fos.write(decMeta);
            }
            metaFile.setReadOnly();
            Log.i(TAG, "Deployed decrypted IL2CPP metadata: " + metaFile.getAbsolutePath()
                    + " (" + decMeta.length + " bytes)");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to deploy IL2CPP metadata (non-fatal)", t);
        }
    }

    /** DEX를 code_cache에 기록하고 0444 적용. */
    private static void writeDexFile(File dexFile, byte[] dexBytes) throws IOException {
        File parent = dexFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // 기존 파일이 있으면 쓰기 가능으로 바꾼 뒤 삭제
        if (dexFile.exists()) {
            dexFile.setWritable(true);
            dexFile.delete();
        }

        try (FileOutputStream fos = new FileOutputStream(dexFile)) {
            fos.write(dexBytes);
            fos.flush();
        }

        ensureReadOnly(dexFile);
        Log.i(TAG, "Wrote DEX file: " + dexFile.getAbsolutePath() + " (" + dexBytes.length + " bytes)");
    }

    // =========================================================================
    // Android 14+ W^X / DEX 주입
    // =========================================================================

    private static void ensureReadOnly(File dexFile) {
        if (!dexFile.canWrite()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                Os.chmod(dexFile.getAbsolutePath(),
                        OsConstants.S_IRUSR | OsConstants.S_IRGRP | OsConstants.S_IROTH);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Os.chmod 0444 failed, falling back to setReadOnly()", e);
            }
        }
        dexFile.setReadOnly();
    }

    private static BaseDexClassLoader createPatchClassLoader(Context context, File dexFile, ClassLoader parent) {
        // API 26+: InMemoryDexClassLoader avoids W^X disk restrictions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                byte[] dexBytes = readFileBytes(dexFile);
                ByteBuffer buf = ByteBuffer.wrap(dexBytes);
                InMemoryDexClassLoader cl = new InMemoryDexClassLoader(buf, parent.getParent());
                Log.i(TAG, "Created InMemoryDexClassLoader (" + dexBytes.length + " bytes)");
                return cl;
            } catch (Throwable t) {
                Log.w(TAG, "InMemoryDexClassLoader failed, falling back to DexClassLoader", t);
            }
        }
        // Fallback: file-based DexClassLoader
        ensureReadOnly(dexFile);
        File optDir = context.getDir("opt_dex", Context.MODE_PRIVATE);
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        return new DexClassLoader(dexFile.getAbsolutePath(), optDir.getAbsolutePath(), libDir, parent.getParent());
    }

    private static byte[] readFileBytes(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
        }
        return buf;
    }

    /** patch의 dexElements를 target의 dexElements 앞단에 병합 → 겹치는 클래스는 patch가 우선 로드. */
    private static boolean injectFront(BaseDexClassLoader target, BaseDexClassLoader patch) {
        try {
            Field pathListF = field(target, "pathList");
            Object targetPL = pathListF.get(target);
            Object patchPL = pathListF.get(patch);

            Field elemsF = field(targetPL, "dexElements");
            Object targetElems = elemsF.get(targetPL);
            Object patchElems = elemsF.get(patchPL);

            int tLen = Array.getLength(targetElems);
            int pLen = Array.getLength(patchElems);
            Log.i(TAG, "injectFront: patch dexElements=" + pLen + ", target dexElements=" + tLen);
            if (pLen == 0) {
                Log.e(TAG, "Patch ClassLoader has 0 dexElements — DEX load failed!");
                return false;
            }
            Class<?> elemType = targetElems.getClass().getComponentType();
            Object merged = Array.newInstance(elemType, tLen + pLen);
            System.arraycopy(patchElems, 0, merged, 0, pLen);     // patch 먼저
            System.arraycopy(targetElems, 0, merged, pLen, tLen); // 기존 뒤에

            elemsF.set(targetPL, merged);
            Log.i(TAG, "dexElements merged: " + Array.getLength(merged) + " total");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "injectFront failed", t);
            return false;
        }
    }

    private static Field field(Object obj, String name) throws NoSuchFieldException {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { /* 상위 클래스로 */ }
        }
        throw new NoSuchFieldException(name + " not found on " + obj.getClass());
    }

    private static void dumpEnvironmentDiag(Context context) {
        try {
            String sourceDir = context.getApplicationInfo().sourceDir;
            boolean sourceDirExists = sourceDir != null && new File(sourceDir).exists();
            Log.i(TAG, "DIAG sourceDir=" + sourceDir + " exists=" + sourceDirExists);
            String dataDir = context.getApplicationInfo().dataDir;
            Log.i(TAG, "DIAG dataDir=" + dataDir);
            String nativeLib = context.getApplicationInfo().nativeLibraryDir;
            Log.i(TAG, "DIAG nativeLibDir=" + nativeLib);

            // /proc/self/cmdline
            try (java.io.FileInputStream fis = new java.io.FileInputStream("/proc/self/cmdline")) {
                byte[] buf = new byte[256];
                int n = fis.read(buf);
                if (n > 0) {
                    String cmdline = new String(buf, 0, n).replace('\0', ' ').trim();
                    Log.i(TAG, "DIAG /proc/self/cmdline=" + cmdline);
                }
            }
            // Check maps for VA path leaks
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/maps")))) {
                String line;
                int leaked = 0;
                while ((line = br.readLine()) != null) {
                    if (line.contains("io.vax.host")) {
                        if (leaked < 5) Log.w(TAG, "DIAG maps LEAK: " + line);
                        leaked++;
                    }
                }
                if (leaked > 0) Log.w(TAG, "DIAG maps leaked " + leaked + " lines with VA host");
                else Log.i(TAG, "DIAG maps: clean (no VA host leaks)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "DIAG failed", t);
        }
    }
}
