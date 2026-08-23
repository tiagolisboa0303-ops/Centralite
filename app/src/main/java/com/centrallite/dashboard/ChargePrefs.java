package com.centrallite.dashboard;

import android.content.Context;

final class ChargePrefs {
    private static final String FILE = "central_lite_power";
    private static final String ENABLED = "root_charge_control_enabled";

    private ChargePrefs() { }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED, enabled).commit();
    }
}
