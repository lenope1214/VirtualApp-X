package io.vax.hello;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

public class HelloActivity extends Activity {
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
        Log.i("HELLO", info.replace('\n', ' '));
        TextView tv = new TextView(this);
        tv.setTextSize(18);
        tv.setPadding(48, 160, 48, 48);
        tv.setText(info);
        setContentView(tv);
    }
}
