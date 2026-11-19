package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.File;
import java.util.Arrays;

/**
 * Fire TV (this personal build): puts back what a restart or a Fire OS update undoes here, with WRITE_SECURE_SETTINGS
 * (granted once over adb): the screensaver (Amazon puts its own back) and Home on Fire's accessibility service (Amazon
 * turns it off, and the Home button then opens Amazon's home screen). At each start of the TV, after an update of this
 * app, then every 30 minutes (the home screen starts the chain again if it broke).
 *
 * Every 30 minutes it also closes what runs in the background for nothing (KILL_BACKGROUND_PROCESSES, as Android does
 * itself when it needs memory): Amazon's apps from firetv/disabled.txt that turned themselves back on, and the streaming
 * apps left open behind, but never Prime Video, Netflix, Spotify or YouTube (MAIN_APPS). Android only lets this close
 * background processes: the app in front, music that plays (a
 * foreground service) and persistent system pieces stay; Amazon's home screen, Live TV and its tuner (Vewd), settings,
 * keyboards and accessibility services (Home on Fire) are never asked. An app closed this way starts again from its
 * own home the next time.
 *
 * The apps Amazon turns back on (firetv/disabled.txt) can't be turned off from here: Fire OS's adbd doesn't answer a
 * connection from the TV itself (127.0.0.1 or its own address: it accepts, then never replies), so only a computer can
 * (firetv/tidy.sh).
 */
final class FireTidy {
    private FireTidy() {}

    /** Two jobs, as ModeJob keeps one pending per action: the home screen's 12-hour check must not replace the start's. */
    static final String ACTION = "fireTidy", REBIND_ACTION = "fireStart";   // ids 1864 and 1404: apart
    private static final long EVERY_MS = 30 * 60_000L;

    /** Turned off on this TV (firetv/disabled.txt); closed when Amazon turns one back on. */
    private static final String[] JUNK = {
            "com.amazon.tv.acr",
            "com.miui.tv.analytics",
            "com.amazon.device.telemetry.emitter",
            "com.amazon.hybridadidservice",
            "com.amazon.sneakpeek",
            "com.amazon.media.recommendations",
            "com.amazon.tv.democontent.provider",
            "com.amazon.shoptv.client",
            "com.amazon.gamehub",
            "com.amazon.minitv.android.app",
            "com.amazon.bueller.photos",
            "com.amazon.bueller.music",
            "com.amazon.storm.lightning.tutorial",
            "com.amazon.tmm.tutorial",
            "com.amazon.smarthomemapviewapp",
            "com.amazon.vizzini",
            "com.amazon.aria",
            "com.amazon.tv.alexaalerts",
            "com.amazon.tv.alexanotifications",
            "com.amazon.alexadirectivebrokerservice",
            "com.amazon.alexamediaplayer.runtime.ftv",
            "com.amazon.privacypassservice",
            "com.amazon.tahoe",
            "com.amazon.systemnotices",
            "com.amazon.tv.website_launcher",
            "com.amazon.hedwig",
            "com.amazon.wirelessmetrics.service",
            "com.amazon.perfc",
            "com.amazon.perfcollection",
            "com.amazon.dp.logger",
            "com.amazon.logan",
            "com.amazon.neo.minerva.logcat",
            "com.amazon.ceviche",
            "com.amazon.connectivitydiag",
            "com.amazon.spiderpork",
            "com.amazon.tv.developer.dataservice",
            "com.amazon.ale",
            "com.amazon.katoch",
            "com.amazon.device_remote_config_sync_service",
            "com.amazon.tv.forcedotaupdater.v2",
            "com.amazon.tv.easyupgrade",
            "com.amazon.tv.releasenotes",
            "com.amazon.dummy.alarmclock",
            "com.amazon.dummy.calendar",
            "com.amazon.dummy.contacts",
            "com.amazon.dummy.gallery",
            "com.amazon.dummy.music",
            "com.amazon.dummy.settings",
            "com.android.traceur",
            "com.amazon.aca",
            "com.amazon.alexa.datastore.app",
            "com.amazon.alexa.externalmediaplayer.fireos",
            "com.amazon.firetv.androidskillconnectioncapability",
            "com.amazon.audiohome",
            "com.amazon.rtcsessioncontroller",
            "com.amazon.spotify.mediabrowserservice",
            "com.amazon.cloud9",
            "com.amazon.tv.localgallery",
            "com.amazon.tv.ottssocompanionapp",
            "com.ivona.tts.oem",
            "com.svox.pico",
            "com.amazon.ods.kindleconnect",
            "com.amazon.alta.h2clientservice"
    };

    /** Amazon's own media apps beyond the main ones (system apps, so not found by the installed-apps rule). */
    private static final String[] AMAZON_MEDIA = {"com.amazon.bueller.music"};   // not the Appstore: it may be updating an app

    /** The apps used every day: never closed, so they reopen where they were (Prime Video, Netflix, Spotify, YouTube). */
    private static final String[] MAIN_APPS = {
            "com.amazon.firebat", "com.amazon.avod", "com.amazon.amazonvideo.livingroom", "com.netflix.ninja",
            "com.spotify.tv.android", "com.amazon.firetv.youtube", "com.google.android.youtube.tv"};

    /** Never closed: what the TV and this setup need running. */
    private static final java.util.Set<String> KEEP = new java.util.HashSet<>(Arrays.asList(
            "io.freemytv", "io.github.toolicious.homeonfire", "com.amazon.tv.launcher", "com.amazon.tv.livetv",
            "com.amazon.mediatek.livetv", "com.amazon.tv.settings.v2", "com.amazon.tv.settings.core",
            "com.amazon.tv.inputpreference.service", "com.amazon.tv.ime", "com.amazon.device.software.ota",
            "com.vewd.core.service", "com.vewd.core.tis", "com.vewd.core.tvapp", "com.vewd.core.opapp", "com.vewd.core.fvpplayer"));
    private static final String SAVER = "io.freemytv/io.freemytv.ScreensaverAlone";
    private static final String HOME_ON_FIRE = "io.github.toolicious.homeonfire";
    private static final String HIJACK = HOME_ON_FIRE + "/" + HOME_ON_FIRE + ".HijackService";

    /**
     * At the TV's start (after it settles) and after this app's update: also binds Home on Fire's service again. Once
     * (2026-11-19) it stayed bound but stopped answering the Home button until it was turned off and on.
     */
    static void schedule(Context c, long delayMs) {
        if (HomeMode.FIRE_TV) ModeJob.schedule(c, REBIND_ACTION, null, delayMs);
    }

    /** From the home screen: starts the chain again if no check ran for an hour (cheap otherwise: one file's time). */
    static void scheduleIfDue(Context c) {
        if (!HomeMode.FIRE_TV) return;
        File stamp = new File(c.getFilesDir(), "fire-tidy");
        if (stamp.exists() && Math.abs(System.currentTimeMillis() - stamp.lastModified()) < 2 * EVERY_MS) return;
        ModeJob.schedule(c, ACTION, null, 5_000);
    }

    /** The check itself (ModeJob, main process). */
    static void runNow(Context c, boolean rebind) {
        if (!HomeMode.FIRE_TV) return;
        File stamp = new File(c.getFilesDir(), "fire-tidy");
        try {
            if (!stamp.createNewFile()) stamp.setLastModified(System.currentTimeMillis());
        } catch (java.io.IOException ignored) {}
        settings(c, rebind);
        closeIdle(c);
        ModeJob.schedule(c, ACTION, null, EVERY_MS);   // the next one
    }

    /** Closes the background processes of Amazon's turned-off apps that came back, and of streaming apps left behind. */
    private static void closeIdle(Context c) {
        android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
        PackageManager pm = c.getPackageManager();
        if (am == null) return;
        java.util.Set<String> keep = new java.util.HashSet<>(KEEP);
        keep.addAll(Arrays.asList(MAIN_APPS));
        // Keyboards and accessibility services: closing one would turn it off until the TV restarts.
        android.view.inputmethod.InputMethodManager imm = c.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) for (android.view.inputmethod.InputMethodInfo i : imm.getEnabledInputMethodList()) keep.add(i.getPackageName());
        String a11y = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (a11y != null) for (String s : a11y.split(":")) if (s.contains("/")) keep.add(s.substring(0, s.indexOf('/')));
        java.util.Set<String> close = new java.util.LinkedHashSet<>();
        int back = 0;
        for (String pkg : JUNK) {
            try {
                int state = pm.getApplicationEnabledSetting(pkg);
                if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    close.add(pkg);
                    back++;
                }
            } catch (IllegalArgumentException e) {   // not on this TV
            }
        }
        close.addAll(Arrays.asList(AMAZON_MEDIA));
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            android.content.pm.ApplicationInfo ai = r.activityInfo.applicationInfo;
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) close.add(ai.packageName);   // installed by you
        }
        int asked = 0;
        for (String pkg : close) {
            if (keep.contains(pkg) || !installed(c, pkg)) continue;
            try {
                am.killBackgroundProcesses(pkg);
                asked++;
            } catch (RuntimeException e) {
                AppLog.w("Fire TV tidy: can't close " + pkg + ": " + e.getMessage());
            }
        }
        AppLog.w("Fire TV tidy: background closed for " + asked + " apps" + (back > 0 ? ", " + back
                + " of Amazon's turned back on (firetv/tidy.sh turns them off)" : ""));
    }

    /** Our screensaver, and Home on Fire's service (beside whatever else is on, like our key service). */
    private static void settings(Context c, boolean rebind) {
        try {
            String saver = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
            if (!SAVER.equals(saver)) {
                Settings.Secure.putString(c.getContentResolver(), "screensaver_components", SAVER);
                AppLog.w("Fire TV tidy: screensaver back to Free My TV (was " + saver + ")");
            }
            if (!installed(c, HOME_ON_FIRE)) return;
            String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on == null || on.equals("null")) on = "";
            boolean there = Arrays.asList(on.split(":")).contains(HIJACK);
            if (there && rebind) {
                // Off, then on: Android unbinds it and binds it again.
                StringBuilder rest = new StringBuilder();
                for (String s : on.split(":")) if (!s.isEmpty() && !s.equals(HIJACK)) rest.append(rest.length() > 0 ? ":" : "").append(s);
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.toString());
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {}
                on = rest.toString();
                there = false;
            }
            if (!there) {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        on.isEmpty() ? HIJACK : HIJACK + ":" + on);
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                AppLog.w(rebind ? "Fire TV tidy: Home on Fire's service bound again" : "Fire TV tidy: Home on Fire's service on again");
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
