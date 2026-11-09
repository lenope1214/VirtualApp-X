package com.lody.virtual.helper.compat;

import android.annotation.TargetApi;
import android.os.Build;

import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;

import java.io.File;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import mirror.com.android.internal.content.NativeLibraryHelper;
import mirror.dalvik.system.VMRuntime;

public class NativeLibraryHelperCompat {

	private static String TAG = NativeLibraryHelperCompat.class.getSimpleName();

	/**
	 * Extracts lib/<abi>/*.so from base + split APKs into one directory. The ABI is chosen across
	 * all APKs together (the arm64 libraries usually live in split_config.arm64_v8a.apk).
	 * Pure zip based, so it does not depend on hidden NativeLibraryHelper internals.
	 */
	public static int copyNativeBinaries(java.util.List<File> apks, File sharedLibraryDir) {
		Set<String> abis = new HashSet<String>();
		for (File apk : apks) {
			Set<String> found = getABIsFromApk(apk.getAbsolutePath());
			if (found != null) {
				abis.addAll(found);
			}
		}
		if (abis.isEmpty()) {
			return 0;
		}
		String abi = null;
		String[] preferred = android.os.Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
		for (String candidate : preferred) {
			if (abis.contains(candidate)) {
				abi = candidate;
				break;
			}
		}
		if (abi == null) {
			VLog.e(TAG, "No usable ABI among %s", abis);
			return -1;
		}
		String prefix = "lib/" + abi + "/";
		int count = 0;
		sharedLibraryDir.mkdirs();
		for (File apk : apks) {
			try (ZipFile zip = new ZipFile(apk)) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();
					if (entry.isDirectory() || name.contains("../") || !name.startsWith(prefix) || !name.endsWith(".so")) {
						continue;
					}
					String leaf = name.substring(prefix.length());
					if (leaf.indexOf('/') >= 0) {
						continue; // nested directory, ignore
					}
					File out = new File(sharedLibraryDir, leaf);
					try (java.io.InputStream in = zip.getInputStream(entry);
						 java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
						byte[] buf = new byte[64 * 1024];
						int n;
						while ((n = in.read(buf)) > 0) {
							os.write(buf, 0, n);
						}
					}
					out.setReadable(true, false);
					out.setExecutable(true, false);
					count++;
				}
			} catch (Exception e) {
				VLog.e(TAG, "extract libs from %s failed: %s", apk.getName(), e);
				return -1;
			}
		}
		VLog.d(TAG, "extracted %d libs (%s) into %s", count, abi, sharedLibraryDir);
		return count;
	}

	public static int copyNativeBinaries(File apkFile, File sharedLibraryDir) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			return copyNativeBinariesAfterL(apkFile, sharedLibraryDir);
		} else {
			return copyNativeBinariesBeforeL(apkFile, sharedLibraryDir);
		}
	}

	private static int copyNativeBinariesBeforeL(File apkFile, File sharedLibraryDir) {
		try {
			return Reflect.on(NativeLibraryHelper.TYPE).call("copyNativeBinariesIfNeededLI", apkFile, sharedLibraryDir)
					.get();
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return -1;
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private static int copyNativeBinariesAfterL(File apkFile, File sharedLibraryDir) {
		try {
			Object handle = NativeLibraryHelper.Handle.create.call(apkFile);
			if (handle == null) {
				return -1;
			}

			String abi = null;
			Set<String> abiSet = getABIsFromApk(apkFile.getAbsolutePath());
			if (abiSet == null || abiSet.isEmpty()) {
				return 0;
			}
			boolean is64Bit = VMRuntime.is64Bit.call(VMRuntime.getRuntime.call());
			if (is64Bit && isVM64(abiSet)) {
				if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
					int abiIndex = NativeLibraryHelper.findSupportedAbi.call(handle, Build.SUPPORTED_64_BIT_ABIS);
					if (abiIndex >= 0) {
						abi = Build.SUPPORTED_64_BIT_ABIS[abiIndex];
					}
				}
			} else {
				if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
					int abiIndex = NativeLibraryHelper.findSupportedAbi.call(handle, Build.SUPPORTED_32_BIT_ABIS);
					if (abiIndex >= 0) {
						abi = Build.SUPPORTED_32_BIT_ABIS[abiIndex];
					}
				}
			}

			if (abi == null) {
				VLog.e(TAG, "Not match any abi [%s].", apkFile.getPath());
				return -1;
			}
			return NativeLibraryHelper.copyNativeBinaries.call(handle, sharedLibraryDir, abi);
		} catch (Throwable e) {
			VLog.d(TAG, "copyNativeBinaries with error : %s", e.getLocalizedMessage());
			e.printStackTrace();
		}

		return -1;
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private static boolean isVM64(Set<String> supportedABIs) {
		if (Build.SUPPORTED_64_BIT_ABIS.length == 0) {
			return false;
		}

		if (supportedABIs == null || supportedABIs.isEmpty()) {
			return true;
		}

		for (String supportedAbi : supportedABIs) {
			if ("arm64-v8a".endsWith(supportedAbi) || "x86_64".equals(supportedAbi) || "mips64".equals(supportedAbi)) {
				return true;
			}
		}

		return false;
	}

	private static Set<String> getABIsFromApk(String apk) {
		try {
			ZipFile apkFile = new ZipFile(apk);
			Enumeration<? extends ZipEntry> entries = apkFile.entries();
			Set<String> supportedABIs = new HashSet<String>();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.contains("../")) {
					continue;
				}
				if (name.startsWith("lib/") && !entry.isDirectory() && name.endsWith(".so")) {
					String supportedAbi = name.substring(name.indexOf("/") + 1, name.lastIndexOf("/"));
					supportedABIs.add(supportedAbi);
				}
			}
			return supportedABIs;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

}
