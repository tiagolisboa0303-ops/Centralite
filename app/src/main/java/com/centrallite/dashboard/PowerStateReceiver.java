package com.centrallite.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

/** Receives ignition-power changes even if another navigation/music app is in front. */
public class PowerStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            openDashboard(context, false);
            return;
        }

        boolean shouldWake = Intent.ACTION_POWER_CONNECTED.equals(action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            shouldWake = isCharging(context);
        }
        if (!shouldWake) return;

        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK |
                                PowerManager.ACQUIRE_CAUSES_WAKEUP |
                                PowerManager.ON_AFTER_RELEASE,
                        "CentralLite:IgnitionWake");
                wakeLock.acquire(6000);
            }
        } catch (Exception ignored) { }

        openDashboard(context, true);

        if (wakeLock != null) {
            try { wakeLock.release(); } catch (Exception ignored) { }
        }
    }

    private void openDashboard(Context context, boolean wake) {
        try {
            Intent open = new Intent(context, MainActivity.class);
            open.putExtra(wake ? "wake_from_power" : "shutdown_from_power", true);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(open);
        } catch (Exception ignored) { }
    }

    private boolean isCharging(Context context) {
        try {
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return false;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception ignored) {
            return false;
        }
    }
}
