package fr.alexis.babyphone;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("babyphone", Context.MODE_PRIVATE);
    }

    static String mode(Context c) { return p(c).getString("mode", ""); }
    static void setMode(Context c, String v) { p(c).edit().putString("mode", v).apply(); }

    static int threshold(Context c) { return p(c).getInt("threshold", 55); }
    static void setThreshold(Context c, int v) { p(c).edit().putInt("threshold", v).apply(); }

    static String manualIp(Context c) { return p(c).getString("manual_ip", ""); }
    static void setManualIp(Context c, String v) { p(c).edit().putString("manual_ip", v).apply(); }

    static String lastIp(Context c) { return p(c).getString("last_ip", ""); }
    static void setLastIp(Context c, String v) { p(c).edit().putString("last_ip", v).apply(); }

    static boolean autoListen(Context c) { return p(c).getBoolean("auto_listen", true); }
    static void setAutoListen(Context c, boolean v) { p(c).edit().putBoolean("auto_listen", v).apply(); }

    static boolean alertSound(Context c) { return p(c).getBoolean("alert_sound", true); }
    static void setAlertSound(Context c, boolean v) { p(c).edit().putBoolean("alert_sound", v).apply(); }
}
