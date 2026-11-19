package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.File;
import java.util.Arrays;

/**
 * Fire TV (this personal build): puts back what a restart or a Fire OS update undoes here, with WRITE_SECURE_SETTINGS
 * (granted once over adb): the screensaver (Amazon puts its own back) and Home on Fire's accessibility service (Amazon
 * turns it off, and the Home button then opens Amazon's home screen). At each start of the TV, after an update of this
 * app, and at most every 12 hours from the home screen.
 *
 * The apps Amazon turns back on (firetv/disabled.txt) can't be turned off from here: Fire OS's adbd doesn't answer a
 * connection from the TV itself (127.0.0.1 or its own address: it accepts, then never replies), so only a computer can
 * (firetv/tidy.sh).
 */
final class FireTidy {
    private FireTidy() {}

    static final String ACTION = "fireTidy";
    private static final long EVERY_MS = 12 * 60 * 60_000L;
    private static final String SAVER = "io.freemytv/io.freemytv.ScreensaverAlone";
    private static final String HOME_ON_FIRE = "io.github.toolicious.homeonfire";
    private static final String HIJACK = HOME_ON_FIRE + "/" + HOME_ON_FIRE + ".HijackService";

    /** At the TV's start (after it settles) and after this app's update. */
    static void schedule(Context c, long delayMs) {
        if (HomeMode.FIRE_TV) ModeJob.schedule(c, ACTION, null, delayMs);
    }

    /** From the home screen: again if the last check is 12 hours old (cheap otherwise: one file's time). */
    static void scheduleIfDue(Context c) {
        if (!HomeMode.FIRE_TV) return;
        File stamp = new File(c.getFilesDir(), "fire-tidy");
        if (stamp.exists() && Math.abs(System.currentTimeMillis() - stamp.lastModified()) < EVERY_MS) return;
        schedule(c, 5_000);
    }

    /** The check itself (ModeJob, main process). */
    static void runNow(Context c) {
        if (!HomeMode.FIRE_TV) return;
        File stamp = new File(c.getFilesDir(), "fire-tidy");
        try {
            if (!stamp.createNewFile()) stamp.setLastModified(System.currentTimeMillis());
        } catch (java.io.IOException ignored) {}
        settings(c);
    }

    /** Our screensaver, and Home on Fire's service (beside whatever else is on, like our key service). */
    private static void settings(Context c) {
        try {
            String saver = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
            if (!SAVER.equals(saver)) {
                Settings.Secure.putString(c.getContentResolver(), "screensaver_components", SAVER);
                AppLog.w("Fire TV tidy: screensaver back to Free My TV (was " + saver + ")");
            }
            if (!installed(c, HOME_ON_FIRE)) return;
            String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on == null || on.equals("null")) on = "";
            if (!Arrays.asList(on.split(":")).contains(HIJACK)) {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        on.isEmpty() ? HIJACK : HIJACK + ":" + on);
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                AppLog.w("Fire TV tidy: Home on Fire's service on again");
            }
        } catch (SecurityException e) {
            AppLog.w("Fire TV tidy: no WRITE_SECURE_SETTINGS (" + e.getMessage() + ")");
        }
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
