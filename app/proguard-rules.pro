# Remove routine diagnostics and their unused message construction in release.
# Warnings/errors remain available for actionable playback and update failures.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Retain app-owned WebView bridge methods called from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# This VLC AAR has no consumer rules. Native callbacks resolve Java names directly.
-keep class org.videolan.libvlc.** { *; }

# Google Cast instantiates the provider named in AndroidManifest metadata.
-keep class com.nikhil.niktv.cast.CastOptionsProvider { *; }

# Retain the generated slot checks in the large Compose landing coordinator.
# Optimizing it in 0.6.40 caused a startup ClassCastException while initializing
# remembered UI state. Other methods remain optimized and names may be obfuscated.
-keepclassmembers,allowobfuscation class com.nikhil.niktv.discovery.DiscoveryUiKt {
    private static void DiscoveryPreviewContent(...);
}
