package com.centrallite.dashboard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Treats the paired Ford SYNC connection as the ignition signal. A 30-second
 * delay avoids putting the tablet to sleep during a brief Bluetooth dropout.
 */
public class SyncStateReceiver extends BroadcastReceiver {
    private static final int REQUEST_SLEEP = 6201;

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (context == null || intent == null) return;
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (!isSyncDevice(device)) return;

        String action = intent.getAction();
        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            scheduleSleep(context);
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            cancelSleep(context);
            wakeDashboard(context);
        }
    }

    private boolean isSyncDevice(BluetoothDevice device) {
        if (device == null) return false;
        try {
            String name = device.getName();
            if (name == null) return false;
            String upper = name.toUpperCase(Locale.US);
            return upper.contains("SYNC") || upper.contains("FORD");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void scheduleSleep(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 30000L,
                sleepIntent(context));
    }

    private void cancelSleep(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(sleepIntent(context));
    }

    private PendingIntent sleepIntent(Context context) {
        return PendingIntent.getBroadcast(context, REQUEST_SLEEP,
                new Intent(context, SyncSleepReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void wakeDashboard(final Context context) {
        new Thread(new Runnable() {
            @Override public void run() {
                if (ChargePrefs.isEnabled(context)) {
                    PowerControl.setInputEnabled(true);
                }
            }
        }, "CentralLiteChargeOn").start();

        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK |
                                PowerManager.ACQUIRE_CAUSES_WAKEUP |
                                PowerManager.ON_AFTER_RELEASE,
                        "CentralLite:SyncWake");
                wakeLock.acquire(6000L);
            }
        } catch (Exception ignored) { }

        try {
            Intent open = new Intent(context, MainActivity.class);
            open.putExtra("wake_from_sync", true);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(open);
        } catch (Exception ignored) { }

        if (wakeLock != null) {
            try { wakeLock.release(); } catch (Exception ignored) { }
        }
    }
}
