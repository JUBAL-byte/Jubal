package com.jubal.jubal_lite

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        YouTubeBridge.register(flutterEngine.dartExecutor.binaryMessenger)
    }
}
