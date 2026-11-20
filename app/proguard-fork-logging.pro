# Fork: a release build writes no log lines at all. Upstream log lines print full addon links, and a
# private addon's link carries the user's token, so every android.util.Log call (all levels) and every
# printStackTrace() (to System.err) is removed by R8, in the app and in every library R8 processes (the player, OkHttp and
# the backend client log through android.util.Log). Crash traces from the runtime (AndroidRuntime FATAL)
# and Sentry reports are unaffected. Debug builds are not minified, so they keep every line.
# Only the no-argument printStackTrace: the PrintWriter/PrintStream forms build stack-trace text
# (Kotlin stackTraceToString, auth diagnostics) and must keep working.
# Checked by ReleaseLogStripRulesTest.
# "return 0": calls whose int result is used (e.g. as a Kotlin expression's value) are removed too (live.20 dex check found 26 survivors).
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int) return false;
    public static int v(...) return 0;
    public static int d(...) return 0;
    public static int i(...) return 0;
    public static int w(...) return 0;
    public static int e(...) return 0;
    public static int wtf(...) return 0;
    public static int println(...) return 0;
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
-assumenosideeffects class * extends java.lang.Throwable {
    public void printStackTrace();
}
