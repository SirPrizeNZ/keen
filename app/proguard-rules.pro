# Keen Zero Phase 0 — keep diagnostics and WebView client entry points.

-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}

# Preserve BuildConfig fields used in evidence export.
-keep class com.keenzero.app.BuildConfig { *; }

# Do not obfuscate evidence model field names used in JSON.
-keepclassmembers class com.keenzero.app.diagnostics.** {
    <fields>;
}
