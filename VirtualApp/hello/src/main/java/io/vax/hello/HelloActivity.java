package io.vax.hello;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

public class HelloActivity extends Activity {

    static {
        System.loadLibrary("vaxprobe");
    }

    /** Native dlopen() probe (probe.c): returns "ok" or the dlerror() text. */
    static native String nativeDlopen(String path);

    /** Reads a (small) proc file via libc-backed FileInputStream so IOUniformer hooks apply. */
    private static String readProc(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, cap = 0;
            while ((n = in.read(buf)) > 0 && cap < 1 << 20) { bos.write(buf, 0, n); cap += n; }
            return new String(bos.toByteArray());
        } catch (Throwable t) {
            return "READ-FAIL:" + t.getClass().getSimpleName();
        }
    }

    /** Verifies the sandbox-transparency proc filtering from inside the guest. */
    private static String procSummary() {
        StringBuilder sb = new StringBuilder();
        String cmd = readProc("/proc/self/cmdline").replace('\0', ' ').trim();
        sb.append("\n[proc] cmdline=").append(cmd);
        String maps = readProc("/proc/self/maps");
        int total = 0, leak = 0;
        for (String line : maps.split("\n")) {
            if (line.isEmpty()) continue;
            total++;
            if (line.contains("io.vax.host") || line.contains("libsubstrate")
                    || line.contains("libva-native") || line.contains("libva++")) leak++;
        }
        sb.append("\n[proc] maps lines=").append(total).append(" VA-leak=").append(leak);
        for (String line : readProc("/proc/self/status").split("\n")) {
            if (line.startsWith("Name:") || line.startsWith("TracerPid:"))
                sb.append("\n[proc] ").append(line.trim());
        }
        return sb.toString();
    }

    /** Writes "vax-io" to an absolute path and reports whether it succeeded and exists after. */
    private static String probe(String path) {
        try {
            File f = new File(path);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write("vax-io".getBytes());
            }
            return "\nprobe " + path + " -> wrote, exists=" + f.exists() + " len=" + f.length();
        } catch (Throwable t) {
            return "\nprobe " + path + " -> FAILED " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String info = "HELLO TARGET\npkg=" + getPackageName()
                + "\nuid=" + Process.myUid() + " pid=" + Process.myPid()
                + "\nfiles=" + getFilesDir();
        try {
            File f = new File(getFilesDir(), "probe.txt");
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write("ok".getBytes());
            }
            info += "\nwrite=" + f.exists();
        } catch (Throwable t) {
            info += "\nwrite FAILED " + t;
        }
        // IO redirect probes: write to ABSOLUTE paths the guest does not own on the real
        // filesystem. If IOUniformer redirects them, they land under the host's virtual storage
        // (verify via `run-as io.vax.host`); if not, the write fails or hits the real path.
        info += probe("/data/data/io.vax.hello/vax_io_abs_probe.txt");
        info += probe("/sdcard/vax_io_sdcard_probe.txt");
        // statfs probe: libcore Os.statvfs -> bionic statvfs -> statfs. An absolute path the guest
        // does not own on the real fs succeeds only if statfs is redirected into the virtual tree.
        try {
            android.system.StructStatVfs st = android.system.Os.statvfs("/data/data/io.vax.hello");
            info += "\nstatvfs /data/data/io.vax.hello -> ok blocks=" + st.f_blocks;
        } catch (Throwable t) {
            info += "\nstatvfs /data/data/io.vax.hello -> FAILED " + t;
        }
        // dlopen redirect probe: libz.so is placed only in the host's virtual lib dir for this
        // package. Real /data/data/<pkg>/lib/ does not exist, so success means the exported dlopen
        // hook redirected the absolute path into the sandbox.
        info += "\ndlopen /data/data/io.vax.hello/lib/libz.so -> " + nativeDlopen("/data/data/io.vax.hello/lib/libz.so");
        info += procSummary();
        Log.i("HELLO", info.replace('\n', ' '));
        TextView tv = new TextView(this);
        tv.setTextSize(18);
        tv.setPadding(48, 160, 48, 48);
        tv.setText(info);
        setContentView(tv);
    }
}
