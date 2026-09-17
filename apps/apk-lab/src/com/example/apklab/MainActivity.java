package com.example.apklab;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView info = (TextView) findViewById(R.id.info);
        info.setText(getString(R.string.hello)
                + "\n\nABI: " + Build.SUPPORTED_ABIS[0]
                + "\nDevice: " + Build.MODEL
                + "\nAPI: " + Build.VERSION.SDK_INT
                + "\nJava runtime: " + System.getProperty("java.vm.version"));
    }
}
