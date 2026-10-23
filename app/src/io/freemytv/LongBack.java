package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fire TV: holding Back (about a second) closes the app in front: Home, then its process is stopped once it's in the
 * background (killBackgroundProcesses: a playing service, like Spotify's, keeps running). A short press is left alone:
 * the app gets its Back as always. Runs in RemoteKeyService (the :keys process), so it does next to nothing per key.
 */
final class LongBack {
    private LongBack() {}

    static final boolean ON = RemoteKeys.is("Amazon");
    private static final long HOLD_MS = 900, STOP_AFTER_MS = 1500;
    /** Never closed: the home screens, the system, settings, the keyboard and Home on Fire. */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "io.freemytv", "android", "com.android.systemui", "com.amazon.tv.launcher", "com.amazon.firehomestarter",
            "com.amazon.tv.settings.v2", "com.amazon.tv.ime", "com.amazon.tv.quicksettings.ui",
            "io.github.toolicious.homeonfire"));

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static String front = "";
    private static boolean closing;

    /** The app whose window came to the front (from the service's window-state events). */
    static void front(CharSequence name) {
        if (name == null) return;
        String pkg = name.toString();
        if (!KEEP.contains(pkg)) front = pkg;
        else if (pkg.equals("io.freemytv") || pkg.equals("com.amazon.tv.launcher")) front = "";   // back home
    }

    /**
     * Turns RemoteKeyService on next to the ones already on (Home on Fire's): Android drops it when this app is
     * updated. Needs WRITE_SECURE_SETTINGS, granted once over adb (pm grant io.freemytv ...); without it, nothing.
     */
    static void ensureService(Context c) {
        if (!ON || c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        String me = c.getPackageName() + "/" + RemoteKeyService.class.getName();
        android.content.ContentResolver cr = c.getContentResolver();
        String on = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on != null && Arrays.asList(on.split(":")).contains(me)) return;
        android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                on == null || on.isEmpty() || on.equals("null") ? me : on + ":" + me);
        android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        AppLog.w("long Back: key service on");
    }

    /** True when the key is ours (consumed): the repeats and the release of a long Back. */
    static boolean onKey(AccessibilityService s, KeyEvent e) {
        if (!ON || e.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                closing = false;
                return false;   // the app gets the press as always
            }
            if (closing) return true;
            if (e.getEventTime() - e.getDownTime() < HOLD_MS || front.isEmpty()) return false;
            closing = true;
            close(s, front);
            return true;
        }
        if (closing) {
            closing = false;
            return true;        // the release doesn't reach the app either
        }
        return false;
    }

    private static void close(AccessibilityService s, String pkg) {
        AppLog.w("long Back: closing " + pkg);
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        front = "";
        main.postDelayed(() -> {
            ActivityManager am = (ActivityManager) s.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
        }, STOP_AFTER_MS);
    }
}
