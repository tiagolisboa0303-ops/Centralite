package com.centrallite.dashboard;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Wakes Central Lite when the already-paired Ford SYNC Bluetooth link returns. */
public class SyncStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) return;

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (!isFordSync(device)) return;

        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK |
                                PowerManager.ACQUIRE_CAUSES_WAKEUP |
                                PowerManager.ON_AFTER_RELEASE,
                        "CentralLite:SyncWake");
                wakeLock.acquire(7000);
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

    private boolean isFordSync(BluetoothDevice device) {
        if (device == null) return false;
        try {
            String name = device.getName();
            if (name == null) return false;
            String upper = name.toUpperCase();
            return upper.contains("SYNC") || upper.contains("FORD");
        } catch (Exception ignored) {
            return false;
        }
    }
}
