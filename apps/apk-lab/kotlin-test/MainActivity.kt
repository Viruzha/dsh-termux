package com.example.apklab

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.info).text = buildString {
            append(getString(R.string.hello))
            append("\n\nWritten in Kotlin ")
            append(KotlinVersion.CURRENT)
            append("\nABI: ").append(Build.SUPPORTED_ABIS[0])
            append("\nAPI: ").append(Build.VERSION.SDK_INT)
        }
    }
}
