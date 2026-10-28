# Keep the JavaScript bridge methods reachable: they are called from the WebView
# by name, so shrinking would otherwise strip them.
-keepclassmembers class com.gev.lite.* {
    @android.webkit.JavascriptInterface <methods>;
}
