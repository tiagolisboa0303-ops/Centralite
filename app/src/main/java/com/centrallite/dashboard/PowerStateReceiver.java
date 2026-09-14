package com.centrallite.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts Central Lite after Android boot. Ignition state is determined by Ford SYNC, not charger power. */
public class PowerStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(open);
        } catch (Exception ignored) { }
    }
}
