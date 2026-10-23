package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fire TV: holding Back (0.8 s) closes the app in front: Home, then its process is stopped once it's in the
 * background (killBackgroundProcesses: a playing service, like Spotify's, keeps running). A short press still reaches
 * the app as its Back. Runs in RemoteKeyService (the :keys process), so it does next to nothing per key.
 */
final class LongBack {
    private LongBack() {}

    static final boolean ON = RemoteKeys.is("Amazon");
    private static final long HOLD_MS = 800, STOP_AFTER_MS = 1500;
    /** Never closed: the home screens, the system, settings, the keyboard and Home on Fire. */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "io.freemytv", "android", "com.android.systemui", "com.amazon.tv.launcher", "com.amazon.firehomestarter",
            "com.amazon.tv.settings.v2", "com.amazon.tv.ime", "com.amazon.tv.quicksettings.ui",
            "io.github.toolicious.homeonfire"));

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static String front = "";
    private static boolean held, closing;
    private static Runnable hold;

    /** The app whose window came to the front (from the service's window-state events). */
    static void front(CharSequence name) {
        if (name == null) return;
        String pkg = name.toString();
        front = KEEP.contains(pkg) ? "" : pkg;   // home screens, settings, keyboard: Back stays theirs
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

    /**
     * Back inside an app is ours: Fire TV remotes send no repeats while a key is held, only the press and, later, the
     * release. Held HOLD_MS, the app is closed right away; released sooner, the app gets its Back (as a global action,
     * which comes back here from a virtual device and is let through). On the home screens and Amazon's own screens
     * (front is empty there), Back is left alone.
     */
    static boolean onKey(AccessibilityService s, KeyEvent e) {
        if (!ON || e.getKeyCode() != KeyEvent.KEYCODE_BACK || e.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return held;
            held = !front.isEmpty();
            if (!held) return false;
            closing = false;
            final String pkg = front;
            hold = () -> {
                closing = true;
                close(s, pkg);
            };
            main.postDelayed(hold, HOLD_MS);
            return true;
        }
        if (!held) return false;
        held = false;
        main.removeCallbacks(hold);
        if (!closing) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        closing = false;
        return true;
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
