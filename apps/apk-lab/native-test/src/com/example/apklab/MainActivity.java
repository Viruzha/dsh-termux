package com.example.apklab;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    static { System.loadLibrary("apklab"); }

    private static native String nativeTag();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ((TextView) findViewById(R.id.info)).setText(
                getString(R.string.hello)
                + "\n\nJNI: " + nativeTag()
                + "\nABI: " + Build.SUPPORTED_ABIS[0]
                + "\nAPI: " + Build.VERSION.SDK_INT);
    }
}
