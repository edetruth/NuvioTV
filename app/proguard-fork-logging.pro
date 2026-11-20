# Fork: release builds drop verbose, debug and info log lines (android.util.Log v/d/i), in the app and
# in every library R8 processes. Upstream debug lines print full addon links, and a private addon's link
# carries the user's token. Warn and error lines stay for crash diagnostics; the app routes their links
# and exception text through com.nuvio.tv.core.LogRedact (guarded by LogRedactGuardTest). Debug builds
# are not minified, so they keep every line.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
