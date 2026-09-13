package com.example.csc;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

/** Tests the installed minified APK without depending on its obfuscated Kotlin runtime. */
public final class ReleaseRuntimeCheck extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            ClassLoader loader = getTargetContext().getClassLoader();
            String[] registrars = {
                "com.google.mlkit.common.internal.CommonComponentRegistrar",
                "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
                "com.google.mlkit.vision.text.internal.TextRegistrar"
            };
            for (String name : registrars) Class.forName(name, true, loader).getConstructor().newInstance();
            Class<?> serviceClass = Class.forName("com.example.csc.automation.ScreenAutomationService", true, loader);
            Object service = serviceClass.getConstructor().newInstance();
            for (String name : new String[] { "getChineseRecognizer", "getLatinRecognizer" }) {
                Method getter = serviceClass.getDeclaredMethod(name);
                getter.setAccessible(true);
                if (getter.invoke(service) == null) throw new AssertionError(name);
            }
            output.putString("stream", "PASS: three registrar constructors and both OCR clients initialize in installed Release APK\n");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            output.putString("stream", "FAILED: " + trace);
            finish(Activity.RESULT_CANCELED, output);
        }
    }
}