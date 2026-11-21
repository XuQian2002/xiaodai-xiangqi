package com.k2fsa.sherpa.onnx;

import java.io.File;
import java.util.Locale;

/*
 * Native library loading. On Android the packaged .so files live under the app's
 * nativeLibraryDir and are resolved by System.loadLibrary; the JAR-resource extraction
 * path used on desktop JVMs is intentionally omitted here to stay compatible with
 * minSdk 24 (java.nio.file requires API 26).
 */

public class LibraryUtils {
    // System property to override native library path
    private static final String NATIVE_PATH_PROP = "sherpa_onnx.native.path";
    private static final String LIB_NAME = "sherpa-onnx-jni";

    private static boolean debug = false;

    public static void enableDebug() {
        debug = true;
    }

    public static void disableDebug() {
        debug = false;
    }

    public static void load() {
        // 1. Try to load from an external directory specified by -Dsherpa_onnx.native.path
        if (loadFromSherpaOnnxNativePath()) {
            return;
        }

        // 2. Fall back to the packaged libraries in nativeLibraryDir.
        System.loadLibrary(LIB_NAME);
    }

    // You specify -Dsherpa_onnx.native.path=/path/to/some/dir
    // where /path/to/some/dir contains sherpa-onnx-jni and onnxruntime.
    private static boolean loadFromSherpaOnnxNativePath() {
        String libFileName = System.mapLibraryName(LIB_NAME);
        String nativePath = System.getProperty(NATIVE_PATH_PROP);

        if (nativePath != null) {
            File nativeDir = new File(nativePath);
            File libInDir = new File(nativeDir, libFileName);
            if (nativeDir.isDirectory() && libInDir.exists()) {
                if (debug) {
                    System.out.printf("Loading from: %s\n", libInDir.getAbsolutePath());
                }

                // Load onnxruntime first to avoid picking up a system version.
                String onnxLibFileName = System.mapLibraryName("onnxruntime");
                File onnxruntimeFile = new File(nativeDir, onnxLibFileName);
                if (onnxruntimeFile.exists()) {
                    System.load(onnxruntimeFile.getAbsolutePath());
                }

                System.load(libInDir.getAbsolutePath());
                return true;
            }
        }

        if (debug) {
            System.out.println("nativePath is null");
        }

        return false;
    }

    static boolean isAndroid() {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        String specVendor = System.getProperty("java.specification.vendor", "");
        return vmName.contains("dalvik") || vmName.contains("art") ||
               specVendor.equals("The Android Project");
    }
}
